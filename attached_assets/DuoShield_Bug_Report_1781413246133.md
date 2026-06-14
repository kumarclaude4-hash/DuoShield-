# DuoShield – Complete Bug Report

**Codebase:** `DuoShield--main`  
**Analysis date:** June 2026  
**Total bugs found:** 16

---

## Severity Legend

| Icon | Meaning |
|------|---------|
| 🔴 | Critical — crashes, data loss, or security failure |
| 🟠 | High — feature completely broken |
| 🟡 | Medium — incorrect behaviour, latent crash, or security concern |
| 🔵 | Low — dead code, style, minor inconsistency |

---

## Bug 1 🔴 — Decryption Fails: Stale ECDH Key Never Re-derived on Re-login

**Files:** `SignInActivity.java:456` (`reDeriveEcdhIfNeeded`), `ChatMediaActivity.java:1127` (`reEnsureEcdhKey`)

**Root cause:** Both methods have an early-exit guard that skips re-derivation when *any* shared key already exists in `SecurePrefs`, even if the partner's EC public key has since rotated (e.g. after their reinstall):

```java
// SignInActivity.java
String existing = secure.getString(KEY_SHARED_AES, null);
if (existing != null) return; // ← skips even when partner key changed

// ChatMediaActivity.java
SecretKey existingKey = CryptoInitializer.getSharedKey(this);
if (existingKey != null && partnerPub.equals(storedPartnerPub)) {
    listenForMessages(); // ← storedPartnerPub may be stale
    return;
}
```

Device A encrypts with `ECDH(A_priv, B_pub_new)` while Device B decrypts with `ECDH(B_priv, A_pub_old)` — different secrets → `AEADBadTagException` → every message shows `[Decryption failed]`.

**Fix:**
- In `reDeriveEcdhIfNeeded`: always compare the fetched `partnerPub` against the stored one; re-derive if they differ, even when a shared key exists.
- In `reEnsureEcdhKey`: the existing guard already checks `partnerPub.equals(storedPartnerPub)`, but `storedPartnerPub` may be null on first run — confirm the null-check is handled.

---

## Bug 2 🔴 — Settings "Crashes" Immediately: Lock Timer Fires on Open

**File:** `BaseActivity.java:onResume`, `AppLockManager.java:shouldLock`

**Root cause:** Every activity that extends `BaseActivity` runs this in `onResume()`:

```java
if (AppLockManager.shouldLock(this)) {
    startActivity(new Intent(this, LockScreenActivity.class));
}
```

`shouldLock()` returns true when `(System.currentTimeMillis() - bgTs) > 3 minutes`. When the user opens Settings from the background (phone was in pocket), `onResume` fires immediately and redirects to `LockScreenActivity` before `SettingsActivity` renders anything — appearing as an instant crash/exit.

`LockScreenActivity` is not a `BaseActivity` subclass, so it doesn't redirect itself, but the user never sees Settings at all.

**Fix:** Either reset the lock timer when the user is already actively navigating within the app, or exempt navigation within the app stack:
```java
// In BaseActivity.onResume — don't lock if we just returned from another app activity
AppLockManager.onAppForegrounded(this); // reset timer first
if (AppLockManager.shouldLock(this)) {
    startActivity(new Intent(this, LockScreenActivity.class));
}
```

---

## Bug 3 🔴 — Notifications Never Delivered When App Is Killed

**Files:** `functions/src/onMessageCreated.ts:68`, `DuoShieldMessagingService.java:onNewToken`

**Root cause 1 — Data-only FCM:** The Cloud Function sends a payload with only a `data` block and no `notification` block. On Android 10+, data-only FCM is **not delivered to `onMessageReceived()`** when the app process is killed. The OS only wakes killed apps for FCM messages that contain a `notification` block.

```typescript
// onMessageCreated.ts — MISSING "notification" block
await getMessaging().send({
    token: fcmToken,
    data: { type: "new_message", title: "DuoShield", body: "New message" },
    android: { priority: "high" },
});
```

**Fix:** Add a `notification` block alongside `data`:
```typescript
notification: { title: "DuoShield", body: "New message" },
android: {
    priority: "high",
    notification: { channelId: "duoshield_messages" },
},
```

**Root cause 2 — FCM token race:** `DuoShieldMessagingService.onNewToken()` reads `my_uid` from SharedPreferences. On a fresh install, the FCM token can refresh before `my_uid` is written (it's written in `route()` which runs after Firebase Auth). `myUid` is null → token never uploaded to Firestore → Cloud Function finds no `fcmToken` and silently skips the push.

**Fix:** Use `FirebaseAuth.getInstance().getUid()` directly instead of the SharedPrefs value:
```java
String myUid = FirebaseAuth.getInstance().getUid(); // not prefs.getString("my_uid", null)
```

---

## Bug 4 🔴 — Message Listener Destroyed on Settings Open, Never Restarted

**File:** `ChatMediaActivity.java:onStop / onResume`

**Root cause:** `onStop()` removes and nulls the Firestore listener. `onResume()` does **not** restart it:

```java
@Override protected void onStop() {
    msgListener.remove();
    msgListener = null;   // ← listener gone
}

@Override protected void onResume() {
    markMessagesAsReadAndSeen();
    clearBadge();
    applyWallpaper();
    // ← NO listener restart
}
```

After returning from Settings (or any other activity), no new messages appear for the rest of the session. The user must kill and reopen the app.

**Fix:**
```java
@Override protected void onResume() {
    super.onResume();
    markMessagesAsReadAndSeen();
    clearBadge();
    applyWallpaper();
    if (msgListener == null && conversationId != null) {
        reEnsureEcdhKey(); // restarts listener once key is confirmed
    }
}
```

---

## Bug 5 🔴 — Sent Messages Store Ciphertext in Room: Search Always Returns Zero Results

**File:** `ChatMediaActivity.java:1083`, `SearchHelper.java`, `ExportHelper.java`

**Root cause:** The comment says "save plain text to Room" but the variable passed is `ciphertext`:

```java
// Comment: "save plain text to Room"  ← WRONG
Message stored = new Message(msgId, conversationId, myUid, ciphertext, now, true);
//                                                           ^^^^^^^^^
saveToRoom(stored);
```

`SearchHelper.runSearch()` calls `MessageDao.searchMessages()` which does `WHERE text LIKE '%query%'`. Since sent messages in Room contain Base64-encoded AES-GCM blobs, no plaintext query ever matches. Additionally, `ExportHelper.exportToPdf()` reads from Room and writes raw ciphertext into the exported PDF for every sent message.

Received messages are correct because they are saved with `displayText` (already decrypted).

**Fix:** Pass `plaintext` not `ciphertext`:
```java
Message stored = new Message(msgId, conversationId, myUid, plaintext, now, true);
```

---

## Bug 6 🟠 — `MessageBuilder` Uses Wrong Firestore Field Name: Reply Notifications Always Show as Encrypted

**File:** `MessageBuilder.java:57`, `ChatMediaActivity.java:706`

**Root cause:** `MessageBuilder` (used by `MessageReplyReceiver` for inline notification replies) writes the encryption flag as `"encrypted"`:
```java
doc.put("encrypted", enc);  // MessageBuilder
```

But the message listener in `ChatMediaActivity` reads `"isEncrypted"`:
```java
Boolean isEncFlag = dc.getDocument().getBoolean("isEncrypted");  // always null → false
```

So every message sent via quick-reply from a notification has `wasEncrypted = false`, and decryption is never attempted. The ciphertext appears as raw Base64 in the chat.

**Fix:** Change `MessageBuilder` line 57:
```java
doc.put("isEncrypted", enc);  // was: "encrypted"
```

---

## Bug 7 🟠 — Conversation List Preview Always Shows Encrypted Text or Is Never Updated

**Files:** `ConversationMetaUpdater.java:17`, `ConversationListActivity.java:128`, `ChatMediaActivity.java` (no update call)

**Two separate issues:**

1. `ChatMediaActivity.sendMessage()` **never calls `ConversationMetaUpdater.update()`**, so `lastMessage` on the chat doc is never written for the primary message path. The conversation list shows a blank or stale preview for all messages sent directly.

2. When `lastMessage` *is* written (only by `MessageBuilder` on notification replies), it is encrypted via `EncryptionHelper.encrypt()` before being stored. But `ConversationListActivity` reads it raw:
```java
conv.lastMessage = last != null ? last.toString() : "";  // ← shows Base64 blob
```
No decryption is ever applied to the preview.

**Fix:**
1. Call `ConversationMetaUpdater.update(...)` inside `sendMessage()`'s `addOnSuccessListener`.
2. In `ConversationListActivity`, wrap with `EncryptionHelper.decrypt(ctx, last.toString())` before assigning.

---

## Bug 8 🟠 — `SelfDestructWorker` Deletes Wrong Messages from Firestore

**File:** `SelfDestructWorker.java:75`

**Root cause:** The Firestore deletion query uses `"timestamp"` (the message creation server timestamp) not `"expiresAt"` (the expiry field set at send time):

```java
.whereLessThan("timestamp", new Date(cutoff))  // ← wrong field
```

This means the worker deletes messages **by age** (older than N minutes), not by their configured expiry time. A message with a 24-hour expiry timer set 25 hours ago would not be deleted if `cutoff` corresponds to 60 minutes, and vice versa. The self-destruct feature does not work as intended.

**Fix:**
```java
.whereGreaterThan("expiresAt", 0L)   // has an expiry set
.whereLessThan("expiresAt", cutoff)  // and it has passed
```
Note: Firestore requires a composite index for two inequality filters on different fields — add `expiresAt` to the index or restructure as a single range query on `expiresAt`.

---

## Bug 9 🟠 — `MessageViewModel` Has Three Stale Firestore Field Names

**File:** `MessageViewModel.java:72,76,77`

`MessageViewModel.docToMessage()` reads three fields that don't match the actual Firestore schema:

| Field read by ViewModel | Actual field written | Effect |
|---|---|---|
| `"mediaUrl"` | `"path"` (new Supabase schema) | All media in ViewModel-based UI shows blank |
| `"replyToText"` | `"replyPreview"` | Reply quote previews always null |
| `"destructAt"` | `"expiresAt"` | Self-destruct timer always 0; messages never expire in ViewModel |

```java
m.setMediaUrl(doc.getString("mediaUrl"));          // should be "path"
m.setReplyPreview(doc.getString("replyToText"));   // should be "replyPreview"
Object da = doc.get("destructAt");                  // should be "expiresAt"
```

**Fix:** Update all three to match the production schema.

---

## Bug 10 🟠 — Conversation List Last-Message Preview Is Never Written for Main Send Path

*(See also Bug 7 for the decryption half.)*

`ChatMediaActivity.sendMessage()` writes the message to `chats/{id}/messages` but never updates the parent `chats/{id}` document. There is no `lastMessage`, `lastMessageTs`, or `unread_` increment for any message sent from the main chat UI. The conversation list never updates its preview, timestamp, or unread badge after a message is sent.

Only messages sent via `MessageBuilder` (quick-reply from notification) update the conversation metadata.

---

## Bug 11 🟡 — `WipeHelper` Uses `apply()` (Async); SignIn Screen May Read Stale Prefs

**File:** `WipeHelper.java:25`

```java
prefs.edit().clear().apply();  // async — not guaranteed committed
Intent i = new Intent(ctx, SignInActivity.class);
ctx.startActivity(i);           // starts immediately after
```

`apply()` is asynchronous. `SignInActivity.onStart()` calls `route()` if `currentUser != null`, which reads `my_uid`, `is_paired`, and `conversation_id` from the same prefs. On a fast device this races — stale values may be read before the async clear completes, causing the just-wiped user to be routed to the chat screen instead of pairing.

Compare with `DuressManager` which correctly uses `commit()` (synchronous) for the same pattern.

**Fix:**
```java
prefs.edit().clear().commit();  // synchronous — guaranteed done before startActivity
```

---

## Bug 12 🟡 — Firestore Rules: `recovery` and `identities` Collections Are Publicly Readable

**File:** `firestore.rules:55-69`

```
match /identities/{userId} {
    allow read: if true;   // ← anyone, unauthenticated
}
match /recovery/{uid} {
    allow read: if true;   // ← anyone, unauthenticated
}
```

Any unauthenticated actor can enumerate all `DS-XXXXXXXX → Firebase UID` mappings from `identities` and download all AES-GCM-encrypted credential blobs from `recovery`. The blobs are protected by PBKDF2(recovery code), but:
- The entire encrypted corpus can be downloaded and subjected to offline brute-force.
- The `identities` collection leaks all user IDs and their Firebase UIDs.

**Fix:**
```
match /identities/{userId} {
    allow read: if request.auth != null;
}
match /recovery/{uid} {
    allow read:  if request.auth != null && request.auth.uid == uid;
    allow write: if request.auth != null && request.auth.uid == uid;
}
```

---

## Bug 13 🟡 — `LinkPreviewFetcher` Cache Uses Non-Thread-Safe `HashMap`

**File:** `LinkPreviewFetcher.java:41`

```java
private static final Map<String, Preview> cache = new HashMap<>();
```

`fetch()` is called from `onBindViewHolder` (main thread). The 3-thread background executor simultaneously reads and writes this map without synchronization. This is a classic `HashMap` concurrent-modification — can produce `ConcurrentModificationException` or, on some JVM implementations, an infinite loop in the internal linked list.

**Fix:**
```java
private static final Map<String, Preview> cache = new java.util.concurrent.ConcurrentHashMap<>();
```

---

## Bug 14 🟡 — Voice/Image/Video Messages: `isEncrypted = false` Despite Being Encrypted

**Files:** `ChatMediaActivity.java:480` (`sendVoiceMessage`), `ChatMediaActivity.java:1002` (`sendMediaMessage`)

All media is correctly AES-256-GCM encrypted before upload via `SupabaseStorageHelper.encryptBeforeUpload()`. However the Firestore message document is written with `isEncrypted = false`:

```java
doc.put("isEncrypted", false);  // voice, image, video
```

Currently harmless because `loadMedia` always attempts decryption regardless. But any future code that gates decryption on this flag will silently hand raw ciphertext bytes to the UI, causing broken playback/display.

**Fix:**
```java
doc.put("isEncrypted", CryptoInitializer.getSharedKey(this) != null);
```

---

## Bug 15 🟡 — Supabase Credentials Hardcoded as String Literals

**File:** `SupabaseStorageHelper.java:52-53`

```java
public static final String SUPABASE_URL      = "https://swpwkwniyrvnsmqafnhz.supabase.co";
public static final String SUPABASE_ANON_KEY = "sb_publishable_cKOQKw5gbnM_rgkelAOPZw_gkS_-2Fl";
```

Both values are extractable from the APK via `strings` or `jadx`. While the anon key is "publishable by design" in Supabase's model, hardcoding it as a Java string constant is visible in decompiled bytecode. Any gap in Supabase RLS rules gives direct storage access to anyone with the APK.

**Fix:** Move to `local.properties` → generated `BuildConfig` fields:
```groovy
// build.gradle
buildConfigField "String", "SUPABASE_URL", "\"${localProperties['supabase.url']}\""
buildConfigField "String", "SUPABASE_ANON_KEY", "\"${localProperties['supabase.anon_key']}\""
```

---

## Bug 16 🔵 — `StorageCleanupWorker` Is Defined but Never Scheduled (Dead Code)

**File:** `StorageCleanupWorker.java`

`StorageCleanupWorker` is a complete `Worker` implementation (50 MB cache-trimming logic) but is never registered with `WorkManager` anywhere in the codebase. `DuoShieldApp`, `TempFileCleaner`, and `SelfDestructScheduler` were all checked — none enqueue it.

**Fix:** Either schedule it in `DuoShieldApp.onCreate()` or delete the class.

---

## Complete Summary Table

| # | Severity | File(s) | Bug |
|---|---|---|---|
| 1 | 🔴 Critical | `SignInActivity` + `ChatMediaActivity` | ECDH key not re-derived when partner rotates → all messages `[Decryption failed]` |
| 2 | 🔴 Critical | `BaseActivity.onResume` | Lock timer fires on Settings open → appears as immediate crash |
| 3 | 🔴 Critical | `onMessageCreated.ts` + `DuoShieldMessagingService` | Data-only FCM not delivered on killed app; FCM token race on install |
| 4 | 🔴 Critical | `ChatMediaActivity.onStop/onResume` | Message listener nulled on Settings open, never restarted → no new messages |
| 5 | 🔴 Critical | `ChatMediaActivity:1083` + `SearchHelper` | `ciphertext` saved to Room instead of `plaintext` → search always empty, PDF export shows garbage |
| 6 | 🟠 High | `MessageBuilder:57` | `"encrypted"` field written instead of `"isEncrypted"` → notification replies show raw ciphertext |
| 7 | 🟠 High | `ConversationListActivity` + `ConversationMetaUpdater` | `lastMessage` never updated by main send path; preview shown as encrypted blob when it is written |
| 8 | 🟠 High | `SelfDestructWorker:75` | Firestore deletion queries `"timestamp"` not `"expiresAt"` → self-destruct deletes wrong messages |
| 9 | 🟠 High | `MessageViewModel:72,76,77` | Three stale field names: `mediaUrl`/`replyToText`/`destructAt` → media, replies, expiry all broken in ViewModel UI |
| 10 | 🟠 High | `ChatMediaActivity.sendMessage` | `ConversationMetaUpdater` never called → conversation list preview/badge never updates |
| 11 | 🟡 Medium | `WipeHelper:25` | `apply()` async clear races with `SignInActivity.onStart()` reading stale prefs |
| 12 | 🟡 Medium | `firestore.rules:55-69` | `recovery` and `identities` collections publicly readable without auth |
| 13 | 🟡 Medium | `LinkPreviewFetcher:41` | `HashMap` cache written from 3-thread executor while main thread reads → potential `ConcurrentModificationException` |
| 14 | 🟡 Medium | `ChatMediaActivity:480,1002` | `isEncrypted = false` for voice/image/video despite bytes being encrypted |
| 15 | 🟡 Medium | `SupabaseStorageHelper:52-53` | Supabase URL + anon key hardcoded as string literals, extractable from APK |
| 16 | 🔵 Low | `StorageCleanupWorker.java` | Worker defined but never scheduled — dead code |

---

*Report generated by full static analysis of all 90 Java source files, 2 TypeScript Cloud Functions, and `firestore.rules`.*
