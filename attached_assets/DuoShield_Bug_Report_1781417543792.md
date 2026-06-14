# DuoShield – Bug Report (Updated)

**Codebase:** `DuoShield--main`
**Analysis date:** June 2026 (re-audit)
**Status:** 13 of 16 previously reported bugs are fully fixed and have been removed from this report.
Remaining/new bugs below.

---

## Severity Legend

| Icon | Meaning |
|------|---------|
| 🔴 | Critical — crashes, data loss, or security failure |
| 🟠 | High — feature completely broken |
| 🟡 | Medium — incorrect behaviour, latent crash, or security concern |
| 🔵 | Low — dead code, style, minor inconsistency |

---

## ✅ Previously Reported Bugs — Now Fully Fixed (removed)

The following are confirmed fixed and no longer present:

1. ECDH stale key never re-derived on re-login (`SignInActivity`, `ChatMediaActivity`)
2. Data-only FCM + `onNewToken` UID race (`onMessageCreated.ts`, `DuoShieldMessagingService`)
3. Message listener nulled on Settings open, never restarted (`ChatMediaActivity.onResume`)
4. Ciphertext saved to Room instead of plaintext (`ChatMediaActivity:1154`)
5. `MessageBuilder` writes `"encrypted"` instead of `"isEncrypted"`
6. Conversation list preview never updated / shown as encrypted blob (`ConversationMetaUpdater` now called + decrypted)
7. `MessageViewModel` stale field names (`mediaUrl`/`replyToText`/`destructAt`)
8. `WipeHelper` now uses `commit()` instead of `apply()`
9. `firestore.rules` — `identities`/`recovery` now require auth
10. `LinkPreviewFetcher` now uses `ConcurrentHashMap`
11. Voice/image/video messages now correctly set `isEncrypted`
12. `StorageCleanupWorker` now scheduled in `DuoShieldApp`

---

## Bug A 🔴 — App Lock Is Effectively Disabled: Foreground Reset Runs Before Lock Check

**Files:** `BaseActivity.java` (`onResume`), `ConversationListActivity.java:onStart`, `AppLockManager.java`

**Root cause:** The originally reported fix for "Settings crashes on open due to lock timer" was never applied to `BaseActivity.onResume()` — it still calls `AppLockManager.shouldLock(this)` directly with no timer reset or activity-stack exemption:

```java
// BaseActivity.java — unchanged
protected void onResume() {
    super.onResume();
    if (AppLockManager.shouldLock(this)) {
        startActivity(new Intent(this, LockScreenActivity.class));
    }
}
```

Separately, `ConversationListActivity.onStart()` (and `FakeChatsActivity`, `SignInActivity`, `LockScreenActivity`) call `AppLockManager.onAppForegrounded(this)`, which resets `bgTs = 0`.

Because `onStart()` always runs **before** `onResume()` in the Android lifecycle, the sequence on app reopen is:

1. `ConversationListActivity.onStart()` → `onAppForegrounded()` → `bgTs = 0`
2. `ConversationListActivity.onResume()` (via `BaseActivity`) → `shouldLock()` → `bgTs == 0` → returns `false`

**Result: the lock screen never appears when reopening the app from the background**, regardless of how long the app was backgrounded. The PIN/biometric lock feature is effectively dead — anyone with the phone can open DuoShield directly to the conversation list.

**Fix:** Check `shouldLock()` *before* resetting the timestamp, e.g. move the `shouldLock()` check into `onStart()` (before `onAppForegrounded()` runs), or have `BaseActivity.onResume()` perform its check using a snapshot of `bgTs` taken in `onPause`/`onStop` rather than relying on ordering with subclass `onStart()` overrides. A more robust approach: track lock state centrally in `DuoShieldApp` via `ActivityLifecycleCallbacks` (count of started activities reaching zero = app backgrounded) rather than per-activity overrides.

---

## Bug B 🟠 — Unread Badge Count Never Resets: Grows Forever

**Files:** `ChatMediaActivity.java:markMessagesAsReadAndSeen`, `util/UnreadCountHelper.java`, `util/ReadReceiptHelper.java`, `util/ConversationMetaUpdater.java:21`

**Root cause:** `ConversationMetaUpdater.update()` increments `unread_<recipientUid>` on every sent message:

```java
data.put("unread_" + recipientUid, FieldValue.increment(1));
```

This field is read and displayed in `ConversationListActivity` and `ConversationViewModel` as the unread badge count. However, `ChatMediaActivity.markMessagesAsReadAndSeen()` — called on every `onResume()` — only updates `lastSeen_<myUid>` and per-message `status` fields. It never resets `unread_<myUid>` to `0`.

Two helper classes that *do* perform this reset already exist but are **never called from the chat screen**:
- `UnreadCountHelper.reset(convId, myUid)` — unused anywhere
- `ReadReceiptHelper.markAllRead(convId, myUid)` — only called from `MarkReadReceiver` (the notification "mark as read" action), not from `ChatMediaActivity`

**Effect:** Opening and reading a conversation never clears its unread badge. The count in `ConversationListActivity` only ever increases, even after all messages have been read normally.

**Fix:** Call `UnreadCountHelper.reset(conversationId, myUid)` (or `ReadReceiptHelper.markAllRead(...)`) from `ChatMediaActivity.markMessagesAsReadAndSeen()`:

```java
private void markMessagesAsReadAndSeen() {
    if (conversationId == null || myUid == null) return;
    db.collection("chats").document(conversationId)
      .update("lastSeen_" + myUid, FieldValue.serverTimestamp());
    UnreadCountHelper.reset(conversationId, myUid);   // ← add this
    ...
}
```

---

## Bug C 🟡 — `SelfDestructWorker` Firestore Query Uses Wrong `cutoff` Value for `expiresAt`

**File:** `db/SelfDestructWorker.java:43,75`

**Root cause:** The field name was corrected from `"timestamp"` to `"expiresAt"`, but the `cutoff` value passed into the Firestore query is still computed the old (age-based) way:

```java
long cutoff = System.currentTimeMillis() - (ttlMinutes * 60_000L);  // a PAST timestamp
...
.whereGreaterThan("expiresAt", 0L)
.whereLessThan("expiresAt", cutoff)   // ← compares future expiry against a past cutoff
```

`expiresAt` is written at send time as `now + disappearMs` — i.e. an **absolute future timestamp** representing when the message should vanish (confirmed by `isExpired()`: `expiresAt > 0 && now > expiresAt`). But `cutoff` here is `now` minus some minutes — a value in the **past**.

`whereLessThan("expiresAt", cutoff)` with `cutoff < now` will essentially never match any message that has a real (future) `expiresAt`, so the Firestore half of self-destruct still does not delete expired messages. (The Room deletion on the line above, `deleteExpired(cutoff)`, is a different, intentionally age-based cleanup of the local cache by `timestamp` and is correct as-is.)

**Fix:** Use the current time, not the age-based `cutoff`, for the Firestore expiry query:

```java
long now = System.currentTimeMillis();
...
.whereGreaterThan("expiresAt", 0L)
.whereLessThan("expiresAt", now)   // compare against "now", not "now - ttl"
```

---

## Bug D 🟡 — Supabase Credentials Still Hardcoded as String Literals

**File:** `util/SupabaseStorageHelper.java:52-53`

**Status:** Unchanged from original report.

```java
public static final String SUPABASE_URL      = "https://swpwkwniyrvnsmqafnhz.supabase.co";
public static final String SUPABASE_ANON_KEY = "sb_publishable_cKOQKw5gbnM_rgkelAOPZw_gkS_-2Fl";
```

Still extractable via `strings`/`jadx` from the APK. Move to `BuildConfig` fields generated from `local.properties` as previously recommended.

---

## Bug E 🟡 — Forwarded / Re-displayed Received Messages Have a Misleading `isEncrypted` Flag in Room

**File:** `ChatMediaActivity.java` (Firestore listener, ~line 752), `util/ForwardMessageHelper.java`

**Root cause:** When a received message is saved to Room, the constructor is called with the **already-decrypted plaintext** (`displayText`) but the `isEncrypted` flag is set to `wasEncrypted` (the flag from the Firestore doc, typically `true`):

```java
Message m = new Message(id, convo, from, displayText, ts, wasEncrypted, mUrl, mType);
```

So a received text message stored in Room has `text = "<plaintext>"` but `isEncrypted = true` — the flag no longer matches the stored content (mirrors the spirit of the original Bug 14, but for *received* messages going into Room rather than Firestore).

**Currently masked by a silent fallback:** `ForwardMessageHelper.forward()` checks `msg.isEncrypted()` and, if true, calls `EncryptionHelper.decrypt(ctx, msg.getText())`. Since `msg.getText()` is already plaintext, `CryptoHelper.decrypt()` throws (invalid Base64/AEAD tag), and `EncryptionHelper.decrypt()`'s catch block returns the input unchanged — so forwarding still works today purely by accident of error-swallowing.

**Fix:** When saving received messages to Room, set `isEncrypted = false` (the stored `text` is plaintext, matching how sent messages are stored per the Bug 5 fix):

```java
Message m = new Message(id, convo, from, displayText, ts, false, mUrl, mType);
```

---

## Bug F 🔵 — `ConversationViewModel` Reads `lastMessage` Without Decrypting (Dead Code)

**File:** `viewmodel/ConversationViewModel.java:44`

**Root cause:** Same issue as the original Bug 7 (part 2), but in a different, unused class:

```java
Object last = snap.get("lastMessage");
conv.lastMessage = last != null ? last.toString() : "";  // not decrypted
```

`ConversationMetaUpdater` stores `lastMessage` encrypted with `EncryptionHelper.encrypt()`. `ConversationListActivity` was fixed to decrypt it, but `ConversationViewModel` (currently unreferenced anywhere in the app) was not.

**Fix:** Either delete `ConversationViewModel` (dead code, like the pre-fix `MessageViewModel`/`StorageCleanupWorker`), or apply the same `EncryptionHelper.decrypt(ctx, last.toString())` fix used in `ConversationListActivity` if it's intended for future use.

---

## Bug G 🔵 — `MessageBuilder` Computes an Unused `encryptedPreview` Variable

**File:** `util/MessageBuilder.java:63-66,74-75`

**Root cause:** `sendTextMessage()` computes `encryptedPreview` (encrypting/truncating the preview text) but then never uses it — `ConversationMetaUpdater.update()` is called with the raw `text` substring instead, and `ConversationMetaUpdater` does its own encryption internally:

```java
String encryptedPreview = enc
    ? (text.length() > 80 ? text.substring(0, 80) : text)
    : EncryptionHelper.encrypt(ctx, text.length() > 80 ? text.substring(0, 80) : text);
// encryptedPreview is never read again
...
ConversationMetaUpdater.update(ctx, convId, myUid, partnerUid,
    text.length() > 80 ? text.substring(0, 80) : text);
```

Functionally harmless (the final result is correct), but dead computation that obscures intent.

**Fix:** Remove the unused `encryptedPreview` block.

---

## Summary Table (Outstanding Issues)

| # | Severity | File(s) | Bug |
|---|---|---|---|
| A | 🔴 Critical | `BaseActivity` / `ConversationListActivity` / `AppLockManager` | App lock never triggers on reopen — `onStart()` resets timer before `onResume()` checks it |
| B | 🟠 High | `ChatMediaActivity.markMessagesAsReadAndSeen`, `UnreadCountHelper`, `ConversationMetaUpdater` | `unread_<uid>` counter never reset — badge grows forever |
| C | 🟡 Medium | `SelfDestructWorker:43,75` | Firestore self-destruct query compares future `expiresAt` against a past `cutoff` — never matches |
| D | 🟡 Medium | `SupabaseStorageHelper:52-53` | Supabase URL + anon key still hardcoded string literals |
| E | 🟡 Medium | `ChatMediaActivity` (listener), `ForwardMessageHelper` | Received messages stored in Room with plaintext `text` but `isEncrypted=true` |
| F | 🔵 Low | `ConversationViewModel:44` | `lastMessage` not decrypted (dead code) |
| G | 🔵 Low | `MessageBuilder:63-66` | Unused `encryptedPreview` computation |

---

*Re-audit performed against the same codebase snapshot; 13/16 original bugs verified fixed via direct source inspection.*
