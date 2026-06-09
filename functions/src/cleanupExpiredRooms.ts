/**
 * cleanupExpiredRooms
 *
 * Runs every 15 minutes via Cloud Scheduler.
 *
 * Deletes pairing room documents whose `expiresAt` timestamp has passed
 * and whose status is still "waiting" (i.e. nobody joined).
 * The Android app already calls cleanupRoom() on success, but this catches
 * rooms that expired before a partner joined.
 *
 * Firestore shape:
 *   rooms/{code}  { status: "waiting"|"paired", expiresAt: number (ms) }
 */

import { onSchedule } from "firebase-functions/v2/scheduler";
import { getFirestore } from "firebase-admin/firestore";
import { logger } from "firebase-functions";

export const cleanupExpiredRooms = onSchedule(
  {
    schedule: "every 15 minutes",
    timeZone: "UTC",
  },
  async () => {
    const db = getFirestore();
    const now = Date.now();

    const expiredSnap = await db
      .collection("rooms")
      .where("status", "==", "waiting")
      .where("expiresAt", "<=", now)
      .get();

    if (expiredSnap.empty) {
      logger.info("cleanupExpiredRooms: no expired rooms found");
      return;
    }

    const batch = db.batch();
    expiredSnap.docs.forEach((doc) => batch.delete(doc.ref));
    await batch.commit();

    logger.info("cleanupExpiredRooms: deleted expired rooms", { count: expiredSnap.size });
  }
);
