/**
 * scheduledSelfDestruct
 *
 * Runs every hour via Cloud Scheduler.
 *
 * Deletes Firestore messages whose `selfDestructAt` timestamp has passed.
 * This is the server-side complement to the Android WorkManager job — it
 * guarantees cleanup even when both devices are offline or uninstalled.
 *
 * Firestore shape expected:
 *   conversations/{convId}/messages/{msgId}  { selfDestructAt: Timestamp }
 *
 * Android side: when sending a message, compute selfDestructAt as:
 *   selfDestructAt = Timestamp.fromMillis(Date.now() + ttlMinutes * 60_000)
 * and include it in the Firestore write. Messages without selfDestructAt
 * are treated as permanent and are never touched by this function.
 */

import { onSchedule } from "firebase-functions/v2/scheduler";
import { getFirestore, Timestamp, WriteBatch } from "firebase-admin/firestore";
import { logger } from "firebase-functions";

const BATCH_SIZE = 100; // Firestore max batch write size

export const scheduledSelfDestruct = onSchedule(
  {
    schedule: "every 60 minutes",
    timeZone: "UTC",
    timeoutSeconds: 540, // 9 min — gives time to sweep large volumes
    memory: "256MiB",
  },
  async () => {
    const db = getFirestore();
    const now = Timestamp.now();
    let totalDeleted = 0;

    logger.info("scheduledSelfDestruct: starting sweep", { now: now.toDate().toISOString() });

    // Iterate all conversations
    const conversationSnap = await db.collection("conversations").get();

    for (const convDoc of conversationSnap.docs) {
      const convId = convDoc.id;

      // Query expired messages in this conversation
      const expiredSnap = await db
        .collection("conversations")
        .doc(convId)
        .collection("messages")
        .where("selfDestructAt", "<=", now)
        .limit(500) // cap per-conversation per-run to avoid timeouts
        .get();

      if (expiredSnap.empty) continue;

      // Delete in batches of BATCH_SIZE
      let batch: WriteBatch = db.batch();
      let batchCount = 0;

      for (const msgDoc of expiredSnap.docs) {
        batch.delete(msgDoc.ref);
        batchCount++;
        totalDeleted++;

        if (batchCount === BATCH_SIZE) {
          await batch.commit();
          logger.info("scheduledSelfDestruct: committed batch", { convId, batchCount });
          batch = db.batch();
          batchCount = 0;
        }
      }

      // Commit any remaining
      if (batchCount > 0) {
        await batch.commit();
        logger.info("scheduledSelfDestruct: committed final batch", { convId, batchCount });
      }
    }

    logger.info("scheduledSelfDestruct: sweep complete", { totalDeleted });
  }
);
