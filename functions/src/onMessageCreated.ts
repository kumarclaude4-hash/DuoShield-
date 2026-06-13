/**
 * notifyOnMessage
 *
 * Triggered whenever a new message lands in:
 *   chats/{chatId}/messages/{messageId}
 *
 * Responsibilities:
 *  1. Look up the chat document to find participants.
 *  2. Identify the recipient (the participant who is NOT the sender).
 *  3. Fetch the recipient's FCM token from users/{recipientUid}.
 *  4. Send a data-only FCM push so the app wakes up silently and syncs.
 *  5. Mark the message document as delivered with a server timestamp.
 *
 * NOTE: PairingManager writes a `participants` array (two UIDs) to the chat
 * document when pairing completes. Without it this function skips gracefully.
 *
 * Firestore shape expected:
 *   chats/{chatId}                     { participants: [uid1, uid2] }
 *   chats/{chatId}/messages/{msgId}    { sender, text, timestamp, isEncrypted, path? }
 *   users/{uid}                        { fcmToken, ecPublicKey }
 */

import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { getFirestore, FieldValue } from "firebase-admin/firestore";
import { getMessaging } from "firebase-admin/messaging";
import { logger } from "firebase-functions";

export const notifyOnMessage = onDocumentCreated(
  "chats/{chatId}/messages/{messageId}",
  async (event) => {
    const snap = event.data;
    if (!snap) return;

    const { chatId, messageId } = event.params;
    const messageData = snap.data();
    const senderUid: string | undefined = messageData?.sender;

    if (!senderUid) {
      logger.warn("notifyOnMessage: message has no sender", { messageId });
      return;
    }

    const db = getFirestore();

    // 1. Load chat to find participants
    const chatDoc = await db.collection("chats").doc(chatId).get();
    if (!chatDoc.exists) {
      logger.warn("notifyOnMessage: chat doc not found", { chatId });
      return;
    }

    const participants: string[] | undefined = chatDoc.data()?.participants;
    if (!participants || participants.length !== 2) {
      logger.warn("notifyOnMessage: participants not set on chat", { chatId });
      return;
    }

    // 2. Identify recipient
    const recipientUid = participants.find((uid) => uid !== senderUid);
    if (!recipientUid) {
      logger.warn("notifyOnMessage: could not determine recipient", { senderUid, participants });
      return;
    }

    // 3. Fetch recipient's FCM token
    const userDoc = await db.collection("users").doc(recipientUid).get();
    const fcmToken: string | undefined = userDoc.data()?.fcmToken;

    if (!fcmToken) {
      logger.info("notifyOnMessage: recipient has no FCM token — skipping push", { recipientUid });
    } else {
      // 4. Send data-only FCM message (no visible notification text — encrypted content stays private)
      try {
        await getMessaging().send({
          token: fcmToken,
          data: {
            type: "new_message",
            chatId,
            messageId,
            // Body text is intentionally vague — actual content is end-to-end encrypted
            title: "DuoShield",
            body: "New message",
          },
          android: {
            priority: "high",
          },
        });
        logger.info("notifyOnMessage: FCM sent", { recipientUid, messageId });
      } catch (err) {
        // A stale/invalid token is common after reinstall — log and continue
        logger.error("notifyOnMessage: FCM send failed", { recipientUid, err });
      }
    }

    // 5. Mark message as delivered with a server timestamp
    try {
      await snap.ref.update({
        status: "delivered",
        deliveredAt: FieldValue.serverTimestamp(),
      });
    } catch (err) {
      logger.error("notifyOnMessage: failed to mark delivered", { messageId, err });
    }
  }
);
