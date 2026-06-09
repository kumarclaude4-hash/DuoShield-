import { onDocumentCreated } from "firebase-functions/v2/firestore";
import { onSchedule } from "firebase-functions/v2/scheduler";
import { initializeApp } from "firebase-admin/app";

initializeApp();

export { notifyOnMessage } from "./onMessageCreated";
export { scheduledSelfDestruct } from "./scheduledSelfDestruct";
export { cleanupExpiredRooms } from "./cleanupExpiredRooms";
