import { initializeApp } from "firebase-admin/app";

initializeApp();

export { notifyOnMessage } from "./onMessageCreated";
export { scheduledSelfDestruct } from "./scheduledSelfDestruct";
export { cleanupExpiredRooms } from "./cleanupExpiredRooms";
