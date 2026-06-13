package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.duoshield.app.models.Conversation;
import com.duoshield.app.ui.ConversationAdapter;
import com.duoshield.app.util.AppLockManager;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import java.util.ArrayList;
import java.util.List;

public class ConversationListActivity extends BaseActivity {

    private static final String TAG = "ConversationListActivity";

    private RecyclerView        recyclerView;
    private ConversationAdapter adapter;
    private View                tvEmpty;
    private EditText            etSearch;

    private FirebaseFirestore    db;
    private ListenerRegistration snapshotListener;
    private String               myUid;
    private String               conversationId;
    private final List<Conversation> allConversations = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation_list);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setTitle("DuoShield");

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUid          = prefs.getString("my_uid", FirebaseAuth.getInstance().getUid());
        conversationId = prefs.getString("conversation_id", null);

        recyclerView = findViewById(R.id.recyclerConversations);
        tvEmpty      = findViewById(R.id.tvEmpty);
        etSearch     = findViewById(R.id.etSearch);

        adapter = new ConversationAdapter(myUid, new ConversationAdapter.OnConversationClickListener() {
            @Override public void onClick(Conversation conv)     { openChat(conv); }
            @Override public void onLongClick(Conversation conv) {}
        });
        if (recyclerView != null) {
            recyclerView.setLayoutManager(new LinearLayoutManager(this));
            recyclerView.setAdapter(adapter);
        }

        db = FirebaseFirestore.getInstance();

        if (etSearch != null) {
            etSearch.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
                @Override public void afterTextChanged(Editable s) {}
                @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                    filterConversations(s.toString().trim());
                }
            });
        }

        // Listener is started in onStart() which always follows onCreate
    }

    private void listenForConversation() {
        // Guard: if already listening, don't create a duplicate listener
        if (snapshotListener != null) return;
        if (conversationId == null) { showEmpty(true); return; }

        snapshotListener = db.collection("conversations").document(conversationId)
            .addSnapshotListener((snap, e) -> {
                if (snap == null || !snap.exists()) return;
                SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
                String partnerUid = prefs.getString("partner_uid", "");

                Conversation conv  = new Conversation();
                conv.id            = conversationId;
                conv.partnerUid    = partnerUid;

                Object pName = snap.get("partnerName_" + myUid);
                conv.partnerName = pName != null ? pName.toString() : "Partner";

                Object last = snap.get("lastMessage");
                conv.lastMessage = last != null ? last.toString() : "";

                Object ts = snap.get("lastMessageTs");
                conv.lastMessageTs = ts instanceof com.google.firebase.Timestamp
                    ? ((com.google.firebase.Timestamp) ts).toDate().getTime() : 0;

                Object unread = snap.get("unread_" + myUid);
                conv.unreadCount = unread instanceof Long ? ((Long) unread).intValue() : 0;

                Object typing = snap.get("typing_" + partnerUid);
                conv.isTyping = Boolean.TRUE.equals(typing);

                Object online = snap.get("online_" + partnerUid);
                conv.isOnline = Boolean.TRUE.equals(online);

                Object muted = snap.get("muted_" + myUid);
                conv.isMuted = Boolean.TRUE.equals(muted);

                allConversations.clear();
                allConversations.add(conv);
                String query = etSearch != null ? etSearch.getText().toString().trim() : "";
                filterConversations(query);
            });
    }

    private void filterConversations(String query) {
        if (query.isEmpty()) {
            adapter.setConversations(new ArrayList<>(allConversations));
        } else {
            List<Conversation> filtered = new ArrayList<>();
            for (Conversation c : allConversations) {
                String name = c.partnerName != null ? c.partnerName.toLowerCase() : "";
                if (name.contains(query.toLowerCase())) filtered.add(c);
            }
            adapter.setConversations(filtered);
        }
        showEmpty(adapter.getItemCount() == 0);
    }

    private void openChat(Conversation conv) {
        startActivity(new Intent(this, ChatMediaActivity.class));
    }

    private void showEmpty(boolean empty) {
        if (tvEmpty      != null) tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (recyclerView != null) recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.conversation_list_menu, menu);
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, com.duoshield.app.ui.SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_key_fingerprint) {
            startActivity(new Intent(this, KeyFingerprintActivity.class));
            return true;
        }
        if (id == R.id.action_search) {
            startActivity(new Intent(this, MessageSearchActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onStart() {
        super.onStart();
        AppLockManager.onAppForegrounded(this);
        listenForConversation();  // safe: guarded by snapshotListener != null check
    }

    @Override protected void onStop() {
        super.onStop();
        if (snapshotListener != null) { snapshotListener.remove(); snapshotListener = null; }
    }

    @Override protected void onResume() { super.onResume(); }
}
