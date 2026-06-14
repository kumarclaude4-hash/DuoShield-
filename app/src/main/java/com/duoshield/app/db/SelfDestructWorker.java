package com.duoshield.app.db;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.QuerySnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.Date;
import java.util.List;

public class SelfDestructWorker extends Worker {

    private static final String TAG          = "SelfDestructWorker";
    private static final int    BATCH_LIMIT  = 100;

    public SelfDestructWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences("duoshield_prefs", Context.MODE_PRIVATE);

        String conversationId  = prefs.getString("conversation_id", null);
        long   ttlMinutes      = prefs.getLong("self_destruct_minutes", 60L);

        if (conversationId == null) {
            Log.d(TAG, "No conversation — nothing to delete.");
            return Result.success();
        }

        // cutoff: age-based past timestamp used for local Room cleanup by insertion time.
        // now:    current time used for Firestore expiresAt comparison (absolute future deadline).
        long now    = System.currentTimeMillis();
        long cutoff = now - (ttlMinutes * 60_000L);

        try {
            // 1. Delete from local Room DB (WorkManager thread — safe to call directly)
            // Bug 16 fix: pass 'now' (not 'cutoff') to deleteExpired(). The DAO query is
            // "WHERE expiresAt > 0 AND expiresAt < :currentTime" — it expects the current
            // time so that all messages whose absolute expiry deadline has passed are deleted.
            // Passing 'cutoff' (= now - ttlMinutes) would miss messages that expired
            // within the last ttlMinutes window.
            AppDatabase db = AppDatabase.getInstance(getApplicationContext());
            db.messageDao().deleteExpired(now);
            Log.d(TAG, "Room: deleted messages with expiresAt < " + now);

            // 2. Delete from Firestore in batches
            deleteFromFirestore(conversationId, now);

            return Result.success();

        } catch (Exception e) {
            Log.e(TAG, "SelfDestructWorker failed — will retry", e);
            return Result.retry();
        }
    }

    /**
     * Queries Firestore for messages older than {@code cutoff} and deletes them
     * in WriteBatches of up to {@value BATCH_LIMIT} documents each.
     * Uses {@link Tasks#await} so we can run synchronously on the WorkManager thread.
     */
    private void deleteFromFirestore(String conversationId, long now) throws Exception {
        FirebaseFirestore firestore = FirebaseFirestore.getInstance();

        // Firestore server timestamps compare cleanly against java.util.Date
        // Query by expiresAt (not timestamp) — that is the expiry deadline set at send time.
        // expiresAt is an absolute future timestamp (now + disappearMs), so we compare against
        // the current time (now), NOT against the age-based cutoff used for local Room cleanup.
        // Both constraints are on the same field so no composite index is required.
        QuerySnapshot snapshots = Tasks.await(
                firestore.collection("chats")
                         .document(conversationId)
                         .collection("messages")
                         .whereGreaterThan("expiresAt", 0L)  // has an expiry set
                         .whereLessThan("expiresAt", now)    // and the deadline has passed
                         .get()
        );

        List<DocumentSnapshot> docs = snapshots.getDocuments();
        if (docs.isEmpty()) {
            Log.d(TAG, "Firestore: no expired messages found.");
            return;
        }

        int total = 0;
        WriteBatch batch = firestore.batch();
        int batchCount = 0;

        for (DocumentSnapshot doc : docs) {
            batch.delete(doc.getReference());
            batchCount++;
            total++;

            if (batchCount == BATCH_LIMIT) {
                Tasks.await(batch.commit());
                Log.d(TAG, "Firestore: committed batch of " + batchCount);
                batch = firestore.batch();
                batchCount = 0;
            }
        }

        // Commit any remaining docs
        if (batchCount > 0) {
            Tasks.await(batch.commit());
        }

        Log.d(TAG, "Firestore: deleted " + total + " expired message(s).");
    }
}
