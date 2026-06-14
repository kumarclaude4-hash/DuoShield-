# DuoShield — Extended Security & Bug Report

**Project:** DuoShield — Secure Android Messaging App  
**Package:** `com.duoshield.app`  
**Report Version:** 2.0 (Original 6 bugs + 14 newly discovered)  
**Analysis Date:** Based on full source code audit of `DuoShield--main.zip`

---

## Part 1 — Original Bugs (Confirmed & Verified)

---

### Bug 1 — Decryption Failure Due to Race Condition

**File:** `ChatMediaActivity.java`  
**Method:** `reEnsureEcdhKey()`  
**Severity:** High

**Root Cause**

A 4-second watchdog fires `listenForMessages()` even if the ECDH shared key has not yet been derived from Firestore:

```java
new Handler(Looper.getMainLooper()).postDelayed(() -> {
    if (msgListener == null) {
        Log.w(TAG, "ECDH watchdog: starting listener without fresh key");
        listenForMessages(); // Starts WITHOUT shared key
    }
}, 4000);
```

If Firestore is slow (poor connection, cold start), the watchdog fires first. Messages arrive, `getSharedKey()` returns `null`, and decryption fails silently.

**Fix (Recommended — Option A)**

Introduce a `boolean keyPending` flag. When key derivation completes, stop and restart the listener:

```java
private volatile boolean keyPending = false;

private void reEnsureEcdhKey() {
    keyPending = true;
    // ... Firestore fetch ...
    // On success:
    keyPending = false;
    if (msgListener != null) { msgListener.remove(); msgListener = null; }
    listenForMessages();
}

// In listenForMessages(), if key arrives mid-stream:
if (keyPending) return; // defer until key is ready
```

**Fix (Option B — Quick):** Increase watchdog to 15 seconds.

---

### Bug 2 — Permanent Decryption Failure After Reinstall / Fresh Login

**Files:** `CryptoInitializer.java`, `ChatMediaActivity.java`, `MainActivity.java`  
**Severity:** Critical

**Root Cause**

After reinstall, `KEY_SHARED_AES` is missing from `EncryptedSharedPreferences`. Additionally, `partnerUid` is populated asynchronously from Firestore in `MainActivity.route()`, but `ChatMediaActivity` may open before the async callback completes. The guard at line 1192 in `ChatMediaActivity` handles `partnerUid == null` by calling `listenForMessages()` immediately without a key:

```java
if (partnerUid == null) {
    listenForMessages(); // Starts without key
    return;
}
```

**Fix**

- Never call `listenForMessages()` when `partnerUid == null`.
- Pass `partnerUid` via Intent extras from `ConversationListActivity` → `ChatMediaActivity` instead of reading from SharedPrefs.
- On reinstall, trigger full ECDH re-derivation before any listener starts.

---

### Bug 3 — Notifications Not Working in Background / Killed State

**File:** `DuoShieldMessagingService.java`  
**Severity:** High

**Root Cause**

`onMessageReceived()` is only invoked by FCM when the app is in the foreground or background (not killed). With a **notification payload**, FCM delivers directly to the system tray and skips `onMessageReceived()` entirely in killed state. With a **data-only payload** in killed state, messages are dropped unless the payload carries `priority: high`.

**Fix (Option A — Preferred)**

Ensure the backend sends data-only payloads with high priority:

```json
{
  "data": { "title": "...", "body": "..." },
  "android": { "priority": "high" }
}
```

**Fix (Option B)**

Send both `notification` (for system-level delivery when killed) and `data` (for foreground handling):

```json
{
  "notification": { "title": "New Message", "body": "..." },
  "data": { "title": "...", "body": "..." }
}
```

---

### Bug 4 — Notification Permission Flow is Broken

**File:** `MainActivity.java`  
**Severity:** Medium

**Root Cause**

Permission is requested but `route()` is called immediately afterwards without waiting for the user's response. `onRequestPermissionsResult()` is not implemented:

```java
ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
// route() is called synchronously below — does not wait for permission result
```

**Fix**

Implement `onRequestPermissionsResult()` and move `route()` inside the callback:

```java
@Override
public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == REQUEST_POST_NOTIFICATIONS) {
        if (results.length == 0 || results[0] != PackageManager.PERMISSION_GRANTED) {
            // Show rationale or redirect to Settings
        }
        route(); // Always proceed, but now inside the callback
    }
}
```

---

### Bug 5 — Delay Before "Decryption Failed" Appears

**Severity:** Medium  
**Root Cause:** Same as Bug 1 — the 4-second watchdog is the source of the perceived lag.

**Fix:** Remove the watchdog entirely and replace with proper key-readiness-gated listener startup (see Bug 1 fix).

---

### Bug 6 — Notification Reply Sends Unencrypted Messages

**File:** `MessageReplyReceiver.java`  
**Severity:** Critical

**Root Cause**

`MessageReplyReceiver` calls `MessageBuilder.sendTextMessage()`. Inspection of `MessageBuilder.java` shows it **does** call `CryptoInitializer.getSharedKey(ctx)`, but the key may be `null` in a killed-state reply scenario (same root cause as Bug 2). When `key == null`, the message is stored with `isEncrypted = false` and sent as plaintext:

```java
SecretKey key = CryptoInitializer.getSharedKey(ctx);
String encrypted = text;
boolean enc = false;
if (key != null) {
    encrypted = CryptoHelper.encrypt(text, key);
    enc = true;
}
// If key is null → enc = false, encrypted = plaintext
```

**Risk:** In killed-state reply, the shared key is not in memory and cannot be recovered from `EncryptedSharedPreferences` without the full app context being properly initialized. Message is stored plaintext in Firestore.

**Fix**

In `MessageReplyReceiver`, check key availability before sending. If key is null, show a notification indicating the reply failed and prompt the user to open the app:

```java
SecretKey key = CryptoInitializer.getSharedKey(context);
if (key == null) {
    // Show fallback notification: "Open app to reply securely"
    return;
}
```

---

## Part 2 — Newly Discovered Bugs

---

### Bug 7 — PIN Hash Stored in Plain (Unencrypted) SharedPreferences

**File:** `PinManager.java`  
**Severity:** High — Security Vulnerability

**Root Cause**

`PinManager` stores the PIN hash in `duoshield_prefs` — a standard, unencrypted `SharedPreferences` file:

```java
ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
   .edit().putString(KEY_PIN, stored).apply();
```

On a rooted device or via an ADB backup (if `android:allowBackup` is accidentally re-enabled), the PBKDF2 hash is exposed. An attacker can run offline dictionary attacks against it.

**Fix**

Store the PIN hash in `SecurePrefs` (EncryptedSharedPreferences), consistent with how ECDH keys are stored:

```java
SecurePrefs.get(ctx).edit().putString(KEY_PIN, stored).apply();
```

---

### Bug 8 — Duress PIN Hash Stored in Plain SharedPreferences

**File:** `DuressManager.java`  
**Severity:** High — Security Vulnerability

**Root Cause**

Same issue as Bug 7. `DuressManager` stores the duress PIN hash in plain `duoshield_prefs`:

```java
context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
       .edit().putString(KEY_DURESS_HASH, stored).apply();
```

If an attacker extracts this hash, they could determine whether a duress PIN is set and attempt to crack it, undermining the entire plausible-deniability mechanism.

**Fix**

Store duress PIN hash in `SecurePrefs`, same as Bug 7 fix.

---

### Bug 9 — `AppLockManager.shouldLock()` Uses Biometric Flag Instead of PIN-OR-Biometric

**File:** `AppLockManager.java`  
**Severity:** Medium

**Root Cause**

`shouldLock()` only returns `true` if **biometric is enabled** OR a PIN is set. But the logic is subtly wrong: if a user sets a PIN but does not enable biometrics, the condition still evaluates correctly — however, if neither is set, the lock is skipped. The real bug is that `shouldLock()` checks `"biometric_enabled"` as the first condition:

```java
if (!prefs.getBoolean("biometric_enabled", false)
        && !PinManager.hasPinSet(ctx)) return false;
```

This is logically correct as written (lock if EITHER is set), but `biometric_enabled` being `true` without a PIN set allows biometric lock without a fallback — and `LockScreenActivity` calls `BiometricHelper.authenticate()` which falls back to `onFailure()` calling `etPin.requestFocus()` on a PIN input that is hidden when no PIN is set.

**Scenario:** User enables biometric, never sets a PIN. Biometric fails (wet fingers, glasses). `onFailure()` fires. PIN field is hidden (`hasPinSet` = false). User is completely locked out.

**Fix**

In `LockScreenActivity.checkPin()`, only show PIN entry if a PIN is actually set. Also add a safety escape hatch (e.g., "Forgot PIN? Wipe & Reset") to avoid permanent lockout.

---

### Bug 10 — `onPause()` in `BaseActivity` Triggers Lock Timer Even Within the App

**File:** `BaseActivity.java`  
**Severity:** Medium

**Root Cause**

`BaseActivity.onPause()` calls `AppLockManager.onAppBackgrounded()`, which writes the current timestamp. This means navigating between any two activities (e.g., Chat → Settings → Chat) triggers the lock timer, even though the user never left the app. If the user spends more than 3 minutes on the Settings screen, they are locked out when returning to Chat.

```java
@Override
protected void onPause() {
    super.onPause();
    AppLockManager.onAppBackgrounded(this); // Fires on every activity transition
}
```

`onAppForegrounded()` is called in `onStart()` of `ConversationListActivity` and `LockScreenActivity`, but NOT in other activities like `ChatMediaActivity` or `SettingsActivity`.

**Fix**

Use `ProcessLifecycleOwner` to track app-level foreground/background instead of per-activity lifecycle hooks, OR call `onAppForegrounded()` in `BaseActivity.onStart()` for all activities.

---

### Bug 11 — `WipeHelper` Does Not Delete EncryptedSharedPreferences (Crypto Keys Survive Wipe)

**File:** `WipeHelper.java`  
**Severity:** Critical — Security Vulnerability

**Root Cause**

`WipeHelper.wipeAll()` clears `duoshield_prefs` and deletes the Room database and cache, but it does NOT clear `duoshield_secure_prefs` (the EncryptedSharedPreferences file created by `SecurePrefs`). After a wipe, the EC private key, EC public key, and shared AES key all survive in storage.

```java
prefs.edit().clear().commit(); // Only clears duoshield_prefs
// duoshield_secure_prefs is never touched
```

**Risk:** A forensic extraction after a duress wipe can recover all cryptographic key material and decrypt past messages if the Firestore conversation data is separately obtained.

**Fix**

```java
// Also wipe EncryptedSharedPreferences
SecurePrefs.get(ctx).edit().clear().commit();

// Also delete the Room DB file directly for complete erasure
ctx.deleteDatabase("duoshield_db");
```

---

### Bug 12 — `DuressManager.triggerDuress()` Does Not Wipe EncryptedSharedPreferences

**File:** `DuressManager.java`  
**Severity:** Critical — Security Vulnerability

**Root Cause**

Same as Bug 11. `triggerDuress()` calls `prefs.edit().clear().commit()` on `duoshield_prefs` only. The ECDH private key and shared AES key in `duoshield_secure_prefs` are completely untouched by the duress wipe.

```java
prefs.edit().clear().commit(); // Only clears duoshield_prefs, NOT SecurePrefs
```

This is especially severe because the duress feature's entire purpose is emergency data erasure. An attacker recovering a wiped device can still decrypt all captured ciphertext.

**Fix**

```java
SecurePrefs.get(context).edit().clear().commit();
context.deleteDatabase("duoshield_db");
// Then proceed with existing Firestore deletion
```

---

### Bug 13 — `FirebaseCostGuard` Is Instantiated Per-Call, Resetting Quota Tracking

**File:** `FirebaseCostGuard.java` (and callers)  
**Severity:** Medium

**Root Cause**

`FirebaseCostGuard` is not a singleton. Every time a new instance is created, `rolloverIfNeeded()` is called, which checks the day key against SharedPreferences. However, since `reads/writes/deletes` counters are stored in SharedPreferences and are read fresh each call, this works correctly at a basic level — but the `warnIfNearing()` method only fires its warning if `wasBelow` is true at the moment of instantiation. If a new instance is created after the threshold is crossed, `wasBelow` will be `false` and the warning is never shown again:

```java
boolean wasBelow = current < (int)(max * WARN_PCT);
boolean isAtOrAbove = (current) >= (int)(max * WARN_PCT);
if (wasBelow && isAtOrAbove) { // Never true if current is already above threshold
```

This means the 80% quota warning fires at most once per app session (not reliably).

**Fix**

Make `FirebaseCostGuard` a singleton with `getInstance(Context)`, or track a `warned_reads/writes/deletes` boolean in SharedPreferences to know whether the warning has been shown today.

---

### Bug 14 — `ConversationListActivity` Decrypts `lastMessage` with AndroidKeyStore Key, Not ECDH Key

**File:** `ConversationListActivity.java`, `EncryptionHelper.java`  
**Severity:** High

**Root Cause**

`ConversationListActivity` decrypts conversation previews using `EncryptionHelper.decrypt()`. `EncryptionHelper` uses `CryptoInitializer.getSharedKey()` (ECDH key) but falls back to `KeyManager.getKey()` (AndroidKeyStore AES key) when shared key is null:

```java
private static SecretKey getKey(Context ctx) throws Exception {
    SecretKey shared = CryptoInitializer.getSharedKey(ctx);
    if (shared != null) return shared;
    return KeyManager.getKey(); // AndroidKeyStore fallback
}
```

However, `ConversationMetaUpdater.update()` (called from `MessageBuilder`) writes the `lastMessage` preview — it calls `EncryptionHelper.encrypt()` which uses the same fallback logic. If the `lastMessage` was written with the ECDH key but is read back with the AndroidKeyStore key (or vice versa), decryption throws an `AEADBadTagException` and the preview silently falls back to raw ciphertext.

**Fix**

Remove the AndroidKeyStore fallback from `EncryptionHelper`. All message content should be encrypted exclusively with the ECDH shared key. If the shared key is unavailable, do not encrypt (or do not write the preview at all):

```java
private static SecretKey getKey(Context ctx) throws Exception {
    return CryptoInitializer.getSharedKey(ctx); // No fallback — null means no encryption
}
```

---

### Bug 15 — `PairingManager.finalizeConnection()` Silently Completes Pairing Without a Shared Key

**File:** `PairingManager.java`  
**Severity:** High

**Root Cause**

When `fetchPartnerKeyWithRetry()` exhausts all 8 retries without obtaining the partner's EC public key, `finalizeConnection()` is called with `partnerPubB64 = null`. The ECDH block is skipped, `KEY_SHARED_AES` is never written, and `onPaired()` is still called. The user is sent to chat with no encryption key:

```java
if (partnerPubB64 != null) {
    // ECDH derivation — skipped silently if null
}
// ...
callback.onPaired(); // Called regardless
```

The user now has a paired state, a conversation ID, but no shared key. All messages will be sent unencrypted (`isEncrypted = false`).

**Fix**

Do not call `onPaired()` if the shared key could not be derived. Show a pending state and poll for the partner's key in the background, completing pairing only when the key is available:

```java
if (partnerPubB64 == null) {
    callback.onError("Partner not yet online. Please ask them to open DuoShield and try again.");
    return;
}
```

---

### Bug 16 — `SelfDestructWorker` Uses Wrong Field for Local Room Deletion

**File:** `SelfDestructWorker.java`, `MessageDao.java`  
**Severity:** Medium

**Root Cause**

`SelfDestructWorker.doWork()` calls `db.messageDao().deleteExpired(cutoff)` where `cutoff` is an **age-based past timestamp** (`now - ttlMinutes * 60_000`):

```java
long cutoff = now - (ttlMinutes * 60_000L);
db.messageDao().deleteExpired(cutoff); // passes cutoff
```

`MessageDao.deleteExpired()` compares this against the `expiresAt` column:

```java
@Query("DELETE FROM messages WHERE expiresAt > 0 AND expiresAt < :now")
void deleteExpired(long now);
```

`expiresAt` is an **absolute future deadline** (set at send time as `now + disappearMs`). Passing a past cutoff timestamp will correctly delete messages whose `expiresAt` has passed — but the variable is named `cutoff` and is computed as an age cutoff, not as the current time. The logic happens to work today, but it is semantically wrong: the comment says "age-based past timestamp" while the DAO expects "current time." Future edits to the `cutoff` formula could silently break expiry.

**Fix**

Pass `now` (not `cutoff`) to `deleteExpired()` to make the intent explicit:

```java
db.messageDao().deleteExpired(now); // expiresAt < now → expired
```

And rename the DAO parameter from `:now` to `:currentTime` for clarity.

---

### Bug 17 — `LockScreenActivity` Wrong PIN Opens Decoy Chats Without Incrementing Fail Counter

**File:** `LockScreenActivity.java`  
**Severity:** Medium

**Root Cause**

The HANDOFF.md mentions a 5-attempt fail counter for wrong PINs, but examining `LockScreenActivity.java`, the `checkPin()` method — after verifying the PIN is wrong — immediately calls `openDecoyChats()` with only a shake animation. There is no counter tracked:

```java
} else {
    HapticHelper.wrongPin(this);
    Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
    etPin.startAnimation(shake);
    etPin.setText("");
    etPin.postDelayed(() -> openDecoyChats(), 550); // Opens decoy — no counter
}
```

The HANDOFF claimed "wrong PIN 5× triggers WipeHelper.wipeAll()" but this is not implemented in the current source. An attacker gets unlimited PIN guesses before seeing decoy chats, with no rate limiting whatsoever.

**Fix**

Add a SharedPreferences-backed fail counter. After N failed attempts (e.g., 10), trigger `WipeHelper.wipeAll()`. Implement exponential backoff (e.g., 30-second delay after 5 failures):

```java
int failCount = prefs.getInt("pin_fail_count", 0) + 1;
prefs.edit().putInt("pin_fail_count", failCount).apply();
if (failCount >= 10) { WipeHelper.wipeAll(this); return; }
```

---

### Bug 18 — `ConversationListActivity` Uses `partnerName` from Firestore Field That Is Never Written

**File:** `ConversationListActivity.java`  
**Severity:** Medium

**Root Cause**

The listener reads the partner's display name from a per-UID Firestore field:

```java
Object pName = snap.get("partnerName_" + myUid);
conv.partnerName = pName != null ? pName.toString() : "Partner";
```

Examining `PairingManager.finalizeConnection()`, `ConversationMetaUpdater`, and all other write paths in the codebase — **no code ever writes `partnerName_<uid>` to the conversation document**. This field will always be `null`, and the conversation list will always show "Partner" instead of a real name.

**Fix**

Write the display name during pairing. In `PairingManager.finalizeConnection()`, after fetching the partner's Firestore user document (which should contain a `displayName`), write it to the chat document:

```java
chatDoc.put("partnerName_" + myUid, partnerDisplayName);
```

---

### Bug 19 — `google-services.json` Contains a Real Firebase Project Reference

**File:** `app/google-services.json`  
**Severity:** High — Security / Operational

**Root Cause**

The repository contains a committed `google-services.json` pointing to the real Firebase project (`duoshield-8caf1`). This file contains the Firebase API key, project ID, app ID, and storage bucket. Combined with the Firestore security rules (not included in this repo, but potentially permissive during development), this gives anyone with the repo the ability to interact with the production Firebase backend.

**File contents (observed):**
```json
{ "project_info": { "project_number": "...", "project_id": "duoshield-8caf1", ... } }
```

**Fix**

1. Add `app/google-services.json` to `.gitignore` immediately.
2. Rotate the Firebase API key in the Firebase console.
3. Ensure Firestore security rules enforce auth-based access (`request.auth != null`).
4. Provide a `google-services.json.template` with placeholder values for new contributors.

---

### Bug 20 — `EncryptionHelper` Silently Returns Plaintext on Encryption Failure

**File:** `EncryptionHelper.java`  
**Severity:** High — Security Vulnerability

**Root Cause**

Both `encrypt()` and `decrypt()` catch all exceptions and silently return the original plaintext:

```java
public static String encrypt(Context ctx, String plaintext) {
    try {
        SecretKey key = getKey(ctx);
        if (key == null) return plaintext; // Silent plaintext fallback
        return CryptoHelper.encrypt(plaintext, key);
    } catch (Exception e) { return plaintext; } // Silent plaintext fallback
}
```

Any transient crypto failure — corrupted key bytes, key derivation exception, cipher initialization failure — results in plaintext being written to Firestore without any indication to the caller or the user.

**Fix**

Throw or propagate the exception. Callers should handle the failure explicitly:

```java
public static String encrypt(Context ctx, String plaintext) throws Exception {
    SecretKey key = getKey(ctx);
    if (key == null) throw new IllegalStateException("No encryption key available");
    return CryptoHelper.encrypt(plaintext, key);
}
```

All callers (`ConversationMetaUpdater`, `ChatMediaActivity`, etc.) should catch the exception and abort the send rather than silently sending plaintext.

---

## Summary Table

| Bug | File(s) | Severity | Category |
|-----|---------|----------|----------|
| Bug 1 | `ChatMediaActivity.java` | High | Reliability |
| Bug 2 | `CryptoInitializer.java`, `MainActivity.java` | **Critical** | Reliability |
| Bug 3 | `DuoShieldMessagingService.java` | High | Notifications |
| Bug 4 | `MainActivity.java` | Medium | UX |
| Bug 5 | `ChatMediaActivity.java` | Medium | UX |
| Bug 6 | `MessageReplyReceiver.java` | **Critical** | Security |
| Bug 7 | `PinManager.java` | High | Security |
| Bug 8 | `DuressManager.java` | High | Security |
| Bug 9 | `AppLockManager.java`, `LockScreenActivity.java` | Medium | Security / UX |
| Bug 10 | `BaseActivity.java` | Medium | UX |
| Bug 11 | `WipeHelper.java` | **Critical** | Security |
| Bug 12 | `DuressManager.java` | **Critical** | Security |
| Bug 13 | `FirebaseCostGuard.java` | Medium | Reliability |
| Bug 14 | `ConversationListActivity.java`, `EncryptionHelper.java` | High | Security |
| Bug 15 | `PairingManager.java` | High | Security |
| Bug 16 | `SelfDestructWorker.java`, `MessageDao.java` | Medium | Reliability |
| Bug 17 | `LockScreenActivity.java` | Medium | Security |
| Bug 18 | `ConversationListActivity.java` | Medium | Functionality |
| Bug 19 | `google-services.json` | High | Security / Ops |
| Bug 20 | `EncryptionHelper.java` | High | Security |

---

## Recommended Fix Priority Order

### Tier 1 — Fix Immediately (Critical Security)

1. **Bug 11** — `WipeHelper` does not erase crypto keys
2. **Bug 12** — Duress wipe leaves crypto keys intact
3. **Bug 6** — Notification reply can send plaintext
4. **Bug 2** — Permanent decryption failure after reinstall
5. **Bug 19** — `google-services.json` committed to repo

### Tier 2 — Fix Before Next Release (High Severity)

6. **Bug 7** — PIN hash in unencrypted prefs
7. **Bug 8** — Duress PIN hash in unencrypted prefs
8. **Bug 20** — Silent plaintext fallback on encryption failure
9. **Bug 15** — Pairing completes without a shared key
10. **Bug 14** — Wrong key used for `lastMessage` preview decryption
11. **Bug 1** — Watchdog race condition on ECDH key derivation
12. **Bug 3** — Notifications silent in killed state

### Tier 3 — Fix in Next Sprint (Medium Severity)

13. **Bug 17** — No PIN brute-force protection
14. **Bug 10** — App-lock timer fires on in-app navigation
15. **Bug 9** — Biometric-only lockout with no PIN fallback
16. **Bug 4** — Notification permission flow broken
17. **Bug 13** — `FirebaseCostGuard` quota warning unreliable
18. **Bug 18** — Partner name never displayed (field never written)
19. **Bug 16** — Wrong variable passed to `deleteExpired()`
20. **Bug 5** — Perceived delay from watchdog

---

## Architectural Notes

All encryption bugs (1, 2, 5, 6, 7, 8, 11, 12, 14, 15, 20) share a common root cause: **key lifecycle is not centrally gated**. The fix pattern is the same in each case — enforce a hard invariant that no message is sent, stored, or displayed without first confirming that a valid, non-null shared key exists.

All wipe bugs (11, 12) share one root cause: `EncryptedSharedPreferences` is never included in the wipe path. A single `SecurePrefs.get(ctx).edit().clear().commit()` call added to both `WipeHelper.wipeAll()` and `DuressManager.triggerDuress()` closes both issues.

The notification reply path (Bug 6) should be treated as a separate message send pipeline that must replicate all security checks present in `ChatMediaActivity.sendMessage()`.

---

*End of Extended Bug Report — 20 Bugs Total (6 Original + 14 Newly Discovered)*
