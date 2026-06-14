# DuoShield — Codebase Analysis

**Package:** `com.duoshield.app`
**Scope:** Full review of `app/src/main/java/com/duoshield/app/**` (92 Java files, ~9,060 LOC), build config, manifest, and repo metadata, cross-referenced against the bundled bug reports and HANDOFF docs.

---

## 1. Overall Architecture

DuoShield is a two-person ("duo") secure messaging app built on:

- **Firebase Auth** for identity, **Firestore** for chat/message storage and presence/typing signals, **FCM** for push.
- **Supabase Storage** for encrypted media blobs (`SupabaseStorageHelper`), with a legacy Firebase media path still partially referenced (`mediaUrl` fallback in `listenForMessages()`).
- **Room** (`AppDatabase`/`MessageDao`) as a local message cache/offline store.
- **End-to-end encryption** via ECDH (P-256) + HKDF-SHA256 → AES-256-GCM, implemented in `crypto/ECDHHelper.java`, `crypto/CryptoHelper.java`, `crypto/CryptoInitializer.java`, `crypto/KeyManager.java`.
- **App-lock / duress / decoy** security layer: `PinManager`, `DuressManager`, `LockScreenActivity`, `FakeChatsActivity`, `WipeHelper`, `AppLockManager`.

The app is clearly the product of an iterative, bug-driven development process — most files contain inline comments documenting a specific "Bug N fix," and most of the 20 bugs catalogued in the attached `DuoShield_Bug_Report_Extended_*.md` have visible, deliberate fixes in the current source. However, several issues remain unresolved, and a few **new issues were introduced by the fixes themselves**. These are detailed below.

---

## 2. Status of Previously Reported Bugs (1–20)

| # | Bug | Status | Notes |
|---|-----|--------|-------|
| 1 | ECDH watchdog race starts listener without key | **Fixed** | `keyPending` flag + 8s watchdog in `ChatMediaActivity.reEnsureEcdhKey()` |
| 2 | Permanent decryption failure after reinstall (`partnerUid == null`) | **Fixed** | `reEnsureEcdhKey()` reloads `partner_uid` from prefs; aborts with a Toast if still null |
| 3 | Notifications dropped in killed state | **Likely fixed at app layer** | `DuoShieldMessagingService`/`NotificationHelper` reviewed; payload shape depends on backend (Cloud Function), not verifiable from this repo alone |
| 4 | Notification permission flow broken | **Fixed** | `onRequestPermissionsResult()` implemented in `MainActivity` |
| 5 | Perceived delay before "Decryption Failed" | **Fixed** (same root cause as #1) | Watchdog increased 4s → 8s, gated by `keyPending` |
| 6 | Notification reply sends plaintext | **Partially fixed** — see §3.1 | `MessageReplyReceiver` now gates on `key == null`, but `MessageBuilder` (the shared send path) still has the dead plaintext fallback and a missing `id` field bug |
| 7 | PIN hash in plaintext SharedPreferences | **Fixed** | `PinManager` now uses `SecurePrefs` (EncryptedSharedPreferences) |
| 8 | Duress PIN hash in plaintext prefs | **Fixed** | `DuressManager` now uses `SecurePrefs` |
| 9 | Biometric-only lockout, no PIN fallback | **Fixed** | `AppLockManager.shouldLock()` requires `PinManager.hasPinSet()` |
| 10 | App-lock timer fires on in-app navigation | **Fixed** | `BaseActivity.onStart()` resets `bgTs` for all `BaseActivity` subclasses |
| 11 | `WipeHelper` doesn't erase crypto keys | **Fixed** | `SecurePrefs.get(ctx).edit().clear().commit()` added first in `wipeAll()` |
| 12 | Duress wipe leaves crypto keys intact | **Fixed** | Same pattern added to `DuressManager.triggerDuress()` |
| 13 | `FirebaseCostGuard` 80% warning unreliable | **Fixed** | Converted to per-day singleton with persistent `warned_<type>` flags |
| 14 | Wrong key for `lastMessage` preview decryption | **Fixed** | `EncryptionHelper.getKey()` now only returns the ECDH shared key, never the AndroidKeyStore fallback |
| 15 | Pairing completes without a shared key | **Fixed** | `PairingManager.fetchPartnerKeyWithRetry()` now calls `callback.onError()` after retries exhaust, instead of silently finalizing with a null key |
| 16 | Wrong variable name passed to `deleteExpired()` | **Fixed / cosmetic** | Not independently re-verified in this pass, but report indicates a rename-only fix |
| 17 | No PIN brute-force protection | **Fixed** | `LockScreenActivity` now tracks `pin_fail_count`, applies 30s backoff after 5 failures, wipes after 10 |
| 18 | `partnerName_<uid>` never written | **Fixed** | `PairingManager.finalizeConnection()` now writes `partnerName_<uid>` for both participants |
| 19 | `google-services.json` committed with real Firebase project | **Not fully fixed** — see §3.2 | `.gitignore` now excludes it and a `.template` exists, but the real file with live API key is **still present in the shipped archive** |
| 20 | `EncryptionHelper` silently returns plaintext on failure | **Fixed** | `encrypt()` now returns `null` on any failure; callers must check for `null` |

**Summary:** 17 of 20 previously reported issues show clear, intentional fixes in the current source. Three (6, 16, 19) are incomplete, and the fix for #6 introduced a related new defect (see §3.1).

---

## 3. Newly Identified Issues

### 3.1 `MessageBuilder` is an unhardened, parallel send path (Bug 6 residue) — **High**

**Files:** `util/MessageBuilder.java`, `notifications/MessageReplyReceiver.java`, `util/ForwardMessageHelper.java`

The primary send path in `ChatMediaActivity.sendMessage()` was correctly hardened: it checks `CryptoInitializer.getSharedKey(this) == null` and aborts with a Toast rather than sending plaintext (this is the Bug 6/20 fix pattern).

However, **`MessageBuilder.sendTextMessage()`** — used by `MessageReplyReceiver` (notification quick-reply) and `ForwardMessageHelper` (message forwarding) — is a second, independent implementation that was *not* updated to match:

```java
SecretKey key = CryptoInitializer.getSharedKey(ctx);
String encrypted = text;
boolean enc = false;
if (key != null) {
    encrypted = CryptoHelper.encrypt(text, key);
    enc = true;
}
// If key == null → enc = false, encrypted = plaintext, sent anyway
```

- `MessageReplyReceiver` happens to be safe in practice because it independently checks `key == null` *before* calling `MessageBuilder` and shows an "Open app to reply securely" notification instead. But this means the safety check is duplicated rather than centralized — any future caller of `MessageBuilder` that forgets this check (such as `ForwardMessageHelper`, see below) reintroduces Bug 6.
- **`ForwardMessageHelper.forward()` has no such guard.** If a user forwards a message before ECDH pairing completes (or after `SecurePrefs` loses the shared key — see §3.3), the forwarded text is sent to Firestore **as plaintext** with `isEncrypted: false` written by `MessageBuilder`. This is a live recurrence of Bug 6 via a different call path.

**Second defect in the same file:** the Firestore document built by `MessageBuilder.sendTextMessage()` **never sets an `"id"` field**:

```java
Map<String, Object> doc = new HashMap<>();
doc.put("sender",    myUid);
doc.put("text",      encrypted);
doc.put("type",      "text");
doc.put("timestamp", FieldValue.serverTimestamp());
doc.put("status",    "sent");
doc.put("isEncrypted", enc);
// no doc.put("id", msgId)
```

Compare with the primary path (`sendMediaMessage`, `sendContactCard`, and the main `sendMessage`), which all explicitly do `doc.put("id", msgId)`.

`ChatMediaActivity.listenForMessages()` reads:
```java
String id = dc.getDocument().getString("id");
...
if (id != null) {
    ... // only path that appends/saves the message
}
```

Because `id` is `null` for any message sent via `MessageBuilder`, **the entire `if (id != null)` block is skipped** — the message is written to Firestore (consuming a write against `FirebaseCostGuard`'s quota) but is **never displayed to either party** and never persisted to Room. From the user's perspective:

- A notification quick-reply appears to succeed (local Room insert with status `"pending"`, never updated since the success callback doesn't touch the screen-level adapter) but the partner never receives it, and re-opening the chat won't show it either (it's absent from the Firestore listener's processed set, though it does still exist as a Firestore document).
- A "forwarded" message silently disappears entirely.

**Recommended fix:**
1. Centralize the "no key → abort, don't send plaintext" check inside `MessageBuilder.sendTextMessage()` itself (call `EncryptionHelper.encrypt()` which already returns `null` on failure — see Bug 20 fix — and abort if `null`), so every caller gets the protection automatically.
2. Add `doc.put("id", msgId)` to the Firestore document in `MessageBuilder`.
3. Update the local Room insert success callback to also push the new message into the currently-open `ChatMediaActivity`'s adapter (e.g., via a shared `LiveData`/`ViewModel` already present in `viewmodel/MessageViewModel.java`) rather than relying solely on the Firestore listener round-trip.

---

### 3.2 `google-services.json` with live Firebase credentials still shipped — **High (Security/Ops)**

**File:** `app/google-services.json`

The Bug 19 fix added the correct `.gitignore` entries and a `google-services.json.template`:

```
# Firebase — google-services.json (Bug 19 fix: must not be committed — contains API keys)
app/google-services.json
```

However, **the real `google-services.json` is still present in the extracted archive**, containing a live `project_id` (`duoshield-8caf1`), `project_number`, `mobilesdk_app_id`, and a populated `api_key.current_key` (`AIzaSyB80HYbZqkgBgGTp9TUkQkJLFrAAfgquhY`).

This means:
- The `.gitignore` entry only prevents *future* commits — it does not remove the file from the working tree or from git history if it was committed previously.
- Anyone with access to this archive (which appears to be a Replit export bundled for handoff, given `attached_assets/` and `replit.md`) has the live Firebase API key for the production project right now.

**Recommended fix:**
1. Rotate the Firebase Android API key in the Firebase/Google Cloud console immediately.
2. Purge `app/google-services.json` from git history (e.g., `git filter-repo` or BFG) if it was ever committed, then re-add only the `.template`.
3. Verify Firestore/Storage security rules enforce `request.auth != null` and per-user/per-chat access control — an exposed (even rotated) API key combined with permissive rules is a much larger exposure than the key alone.
4. For Replit-based workflows, store the real file as a Replit Secret/uploaded asset outside the git-tracked directory, and have the build pull it in at build time.

---

### 3.3 `SecurePrefs` silent fallback to unencrypted storage on `MasterKeys`/`EncryptedSharedPreferences` failure — **High (Security/Reliability)**

**File:** `util/SecurePrefs.java`

```java
public static SharedPreferences get(Context context) {
    try {
        String masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC);
        return EncryptedSharedPreferences.create(
            FILE_NAME, masterKeyAlias, context.getApplicationContext(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        );
    } catch (Exception e) {
        // Fall back gracefully — app remains functional, just not hardware-encrypted
        return context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE);
    }
}
```

This is the storage backing **everything security-critical**: the EC private key, the ECDH shared AES key, the PIN hash, and the duress PIN hash (per Bugs 7/8/11/12/14/15/20 fixes, which all route through `SecurePrefs`).

Two problems:

1. **`androidx.security.crypto:security-crypto` (the `MasterKeys`/`EncryptedSharedPreferences` API) is deprecated** by Google, and the 1.1.0-alpha series had a well-documented issue where `MasterKeys.getOrCreate()` / Keystore key access can throw after certain OS upgrades or Keystore resets (e.g., after a factory-reset-and-restore, or on some OEM Keystore implementations), making previously-written `EncryptedSharedPreferences` permanently unreadable.
2. When that happens, **this code silently falls back to plain, unencrypted `SharedPreferences` using the *same file name*** (`duoshield_secure_prefs`). Two failure modes follow:
   - If the encrypted file still exists on disk but the master key is gone, `getString(...)` calls on the new plain-prefs handle will simply return the defaults (`null`/empty) — `CryptoInitializer.getSharedKey()` returns `null`, `CryptoInitializer.getMyPrivateKey()` returns `null`, and the app behaves as if pairing never happened. This is functionally **identical to Bug 2** (permanent decryption failure) but originates from Keystore/Master-key corruption rather than reinstall, and `reEnsureEcdhKey()`'s reinstall-recovery logic does not address it because `partnerUid` is still present.
   - If `SecurePrefs.get()` throws on a *write* (e.g., during `PairingManager.finalizeConnection()` or `CryptoInitializer.ensureKeyExists()`), subsequent reads/writes silently land in **plaintext** SharedPreferences — meaning the EC private key, ECDH shared AES key, and PIN/duress hashes could end up stored unencrypted on disk going forward, directly undermining Bugs 7, 8, 11, 12, 14, 15, 20.

**Recommended fix:**
- Do not silently fall back to plaintext for crypto-material keys. On `EncryptedSharedPreferences` failure, either (a) surface a hard error state ("Secure storage unavailable — please reinstall") rather than degrading silently, or (b) migrate to `Jetpack Security`'s successor / a Tink-based `AndroidKeystoreKeyManager` implementation that doesn't share the deprecation/corruption profile.
- At minimum, log + report (e.g., via a non-PII analytics event) whenever the catch-block fallback is hit, so this failure mode is observable rather than silent.
- Add a startup self-check: if `SecurePrefs` returns a handle that is *not* an `EncryptedSharedPreferences` instance, treat all crypto material reads from it as untrusted/absent and force re-pairing rather than reading (potentially plaintext-stored) key material.

---

### 3.4 "Decryption failed" / "Waiting for encryption key…" placeholders are permanently persisted — **Medium/High (Reliability)**

**File:** `ChatMediaActivity.java`, `listenForMessages()` (~lines 741–780)

When an encrypted message arrives but the ECDH shared key isn't yet available (`k == null`) or decryption throws, the code stores a **placeholder string** as the message body:

```java
if (wasEncrypted && text != null && !text.isEmpty()) {
    try {
        SecretKey k = CryptoInitializer.getSharedKey(ChatMediaActivity.this);
        if (k != null) {
            displayText = CryptoHelper.decrypt(text, k);
        } else {
            displayText = "[Waiting for encryption key…]";
        }
    } catch (Exception ex) {
        displayText = "[Decryption failed]";
    }
}
...
Message m = new Message(id, convo, from, displayText, ts, false, mUrl, mType);
...
adapter.appendMessage(m);
saveToRoom(m);
```

The comment above this (citing a separate "Bug E fix") explains that `isEncrypted` is deliberately set to `false` on the `Message` object because `displayText` is "already decrypted" — but in the placeholder cases, `displayText` is **not** decrypted plaintext, it's a literal English placeholder string, and it gets:

- Appended to the adapter and shown to the user.
- **Persisted to Room** via `saveToRoom(m)` with `isEncrypted = false`.
- Registered in `adapter.getMessages()` by `id`.

On a later snapshot (e.g., once `reEnsureEcdhKey()` finishes and restarts the listener with the correct key), Firestore re-delivers this same document. The `exists` check at the top of the `ADDED` handling loop finds the message already present by `id`:

```java
for (Message m : adapter.getMessages()) {
    if (id.equals(m.getId())) {
        exists = true;
        // only updates `status`, never re-attempts decryption
        ...
        break;
    }
}
if (exists) continue;
```

So the placeholder is **never replaced** — the message stays as `"[Waiting for encryption key…]"` or `"[Decryption failed]"` for the rest of the session, and (because it was written to Room with `isEncrypted=false`) it will be loaded back from the local cache as a permanent plaintext-looking placeholder on next app launch too. Given that the original support request was specifically about "decryption keeps failing," **this is very likely the proximate cause of the symptom the user is seeing**, even though the underlying key-derivation race (Bugs 1/2/5) has been fixed — the fix prevents *new* sessions from racing, but any message that arrived during the (now-shorter, but still nonzero) window before the key was ready gets permanently stuck.

**Recommended fix:**
- Do not call `saveToRoom(m)` for placeholder text. Either:
  - Keep these messages in a separate "pending decryption" in-memory queue, and re-run decryption against them once `reEnsureEcdhKey()` reports a key is available (without needing a full listener restart), updating the adapter and Room in place; or
  - Mark such `Message` rows with a distinct sentinel (`isEncrypted = true`, body = original ciphertext) so that any future re-render / re-fetch path can retry decryption, and only show the placeholder transiently in the UI layer (not persisted).
- Add a lightweight "retry decryption for visible messages" pass triggered whenever `CryptoInitializer.getSharedKey()` transitions from `null` → non-null (e.g., at the end of `reEnsureEcdhKey()`'s success branches), iterating `adapter.getMessages()` and re-decrypting any message whose stored text still looks like one of the placeholder strings or like ciphertext.

---

### 3.5 Decoy/duress flow: wrong-PIN backoff opens decoy chats *after* the delay, but the delay itself is visible and unexplained — **Low/Medium (UX/Security tradeoff)**

**File:** `LockScreenActivity.java`, `handleWrongPin()`

After 5 failed PIN attempts, the UI shows:
```java
tvError.setText("Too many attempts. Wait " + (delay/1000) + " s…");
```
...before transitioning to the decoy chat screen. While this correctly rate-limits brute-force attempts (Bug 17 fix), displaying an explicit countdown *and then switching to a different chat list* is itself a signal to an attacker that (a) a real PIN exists, (b) they've now triggered rate-limiting, and (c) the screen they're about to see may be a decoy. For a plausible-deniability feature, the rate-limit messaging slightly undercuts the "this is just my chat app" cover story.

**Recommended fix (optional, design tradeoff):** Consider a generic "Incorrect PIN" message with a short fixed delay regardless of attempt count for the first several attempts, only escalating to a visible lockout message after a higher threshold, so the existence of backoff logic itself isn't immediately obvious.

---

### 3.6 Legacy AndroidKeyStore AES key (`duoshield_key`) is generated but effectively orphaned — **Low (Code hygiene)**

**Files:** `crypto/KeyManager.java`, `crypto/CryptoInitializer.java`

`CryptoInitializer.ensureKeyExists()` still generates and maintains the legacy `duoshield_key` AndroidKeyStore AES-256-GCM key ("pre-pairing fallback"), but the Bug 14 fix removed all use of this key from `EncryptionHelper` — `EncryptionHelper.getKey()` now exclusively returns the ECDH shared key. A repo-wide search shows `KeyManager.getKey()` is no longer called from any encrypt/decrypt path.

This isn't actively harmful, but it's dead functionality that:
- Adds an unused AndroidKeyStore entry per install.
- Could confuse future maintainers into thinking pre-pairing messages are encrypted with it (they aren't — pre-pairing, `getSharedKey()` is `null` and the main send path now correctly refuses to send at all).

**Recommended fix:** Either remove `KeyManager`'s AES key generation entirely, or repurpose it for an actual pre-pairing use case (e.g., encrypting locally-cached drafts before a shared key exists) and document that explicitly.

---

### 3.7 Repeating "Today / Yesterday / Today / Yesterday" date headers — **Medium (UX/Reliability)**

**Files:** `ui/MessageAdapter.java` (`rebuildDisplay()`), `util/DateHeaderHelper.java`

**Symptom:** chat date separators alternate ("Today", "Yesterday", "Today", "Yesterday", ...) repeatedly through the message list instead of showing one "Yesterday" header followed by one "Today" header.

**Root cause:** `MessageAdapter.rebuildDisplay()` recomputes a date label for **every message, on every rebuild**, using `DateHeaderHelper.getLabel()`:

```java
private void rebuildDisplay() {
    displayItems.clear();
    String lastDate = null;
    for (Message m : messages) {
        String label = DateHeaderHelper.getLabel(m.getTimestamp());
        if (!label.equals(lastDate)) {
            displayItems.add(label);
            lastDate = label;
        }
        displayItems.add(m);
    }
}
```

`DateHeaderHelper.getLabel(epochMs)` does **not** return a fixed calendar-date label — it returns a label computed **relative to `Calendar.getInstance()` at the moment of the call** (i.e., relative to "now"):

```java
public static String getLabel(long epochMs) {
    Calendar now  = Calendar.getInstance();   // "now" = whenever this is called
    Calendar then = Calendar.getInstance();
    then.setTimeInMillis(epochMs);

    if (isSameDay(now, then)) return "Today";
    now.add(Calendar.DAY_OF_YEAR, -1);
    if (isSameDay(now, then)) return "Yesterday";
    ...
}
```

So the label for the *same message* (same `epochMs`) can change between two different invocations of `rebuildDisplay()` — specifically, a message sent yesterday returns `"Yesterday"` right up until midnight, and a different label (formatted weekday/date) after midnight; conversely a message sent "today" becomes "Yesterday" tomorrow.

`rebuildDisplay()` is called very frequently and is **not idempotent across time** — `notifyDataSetChanged()` is triggered by:
- `appendMessage()` — every new incoming/outgoing message
- `updateMessage()` — every status tick (sent → delivered → read), every reaction, every reply
- `removeMessage()`
- `updatePinnedIds()`
- `setPlayingMessageId()`

Each of these calls `rebuildDisplay()` from scratch, recomputing **every** message's label against the *current* device clock. The only de-duplication is "does this label differ from the immediately preceding item's label" (`!label.equals(lastDate)`) — there is no absolute, stable grouping by calendar date (e.g., a `YYYY-MM-DD` key).

**Why this produces the observed alternating pattern:** as soon as the device clock crosses midnight, any `rebuildDisplay()` call (triggered by something as minor as a read-receipt update, a reaction, or a typing-indicator-driven refresh) re-evaluates every message's label under the *new* "today." Messages that were grouped under one "Today" header before midnight now fall under "Yesterday", while messages arriving after midnight are labeled "Today" — and because the underlying `messages` list isn't bucketed by a stable date key, the header-insertion logic re-derives a fresh, independent label sequence on each rebuild. Once the relative day-boundary classification of consecutive messages can flip between rebuilds (which it will, the instant "now" crosses midnight mid-session), the same date label can be produced multiple times in non-adjacent runs across the list, visually manifesting as repeating "Today"/"Yesterday" headers as the UI keeps refreshing across the day boundary.

`DateHeaderHelper.needsHeader(prev, current)` — a method that *does* compare two message timestamps directly against each other (not against "now") and would correctly determine whether a header is needed between two adjacent messages — **exists but is never called anywhere in the codebase**. This looks like the intended fix that was written but never wired in.

**Recommended fix:**
1. Replace the per-render, "now"-relative labeling with a **stable, absolute calendar-date key** per message (e.g., `yyyyMMdd` derived from `m.getTimestamp()`), computed once and cached/memoized rather than recomputed against "now" on every rebuild.
2. In `rebuildDisplay()`, insert a header **only when the absolute date key changes between consecutive messages** — i.e., use `DateHeaderHelper.needsHeader(prevTimestamp, currentTimestamp)` (already implemented and unused) to decide *whether* to insert a separator, and use `getLabel()` only to *format* that separator's display text (Today/Yesterday/weekday/date) once it's been placed.
3. Decouple "where headers go" (absolute, timestamp-vs-timestamp comparison via `needsHeader()`) from "what text a header shows" (relative-to-now via `getLabel()`) — `getLabel()`'s relative classification is fine for display text but must not drive placement.
4. Optionally, refresh header *text* (not placement) on `onResume()` or via a periodic tick so a header showing "Today" correctly becomes "Yesterday" after midnight without depending on an unrelated `notifyDataSetChanged()` to trigger it.

---

### 3.8 `ConversationListActivity` / chat-list "Partner" fallback may still show stale `"Partner"` for pre-fix pairs — **Low (Functionality)**

**File:** `pairing/PairingManager.java`, `ConversationListActivity.java`

The Bug 18 fix writes `partnerName_<uid>` symmetrically during `finalizeConnection()`. This correctly fixes the issue for **new pairings** going forward. However, any chat pairs that were established *before* this fix shipped will have `chats/{chatId}` documents that still lack `partnerName_<uid>` fields, and will continue showing `"Partner"` indefinitely — `finalizeConnection()` is a one-time operation at pairing and isn't re-run for existing pairs.

**Recommended fix:** Add a lightweight one-time migration: on app start (or on opening a conversation), if `partnerName_<myUid>` is missing from the chat doc, read `users/{partnerUid}.displayName` and backfill it (mirroring the logic already in `finalizeConnection()`).

---

## 4. Things That Are Solid

- The ECDH + HKDF-SHA256 + AES-256-GCM crypto stack (`ECDHHelper`, `CryptoHelper`) is implemented correctly and conservatively (P-256, manual HKDF that avoids Java FIPS key-size constraints, constant-time PIN hash comparison via `constantTimeEquals`, 310,000-round PBKDF2 for PIN/duress hashes).
- The wipe/duress logic (`WipeHelper`, `DuressManager`) now correctly destroys `SecurePrefs` (crypto material) *before* anything else, and deletes the Room DB file directly as a belt-and-suspenders measure against slow async deletes.
- `FirebaseCostGuard`'s per-day singleton + persistent "warned" flags is a clean, idiomatic fix for the original race condition.
- The deterministic `chatId = SHA-256(min(uidA,uidB) + "/" + max(uidA,uidB))` scheme in `PairingManager.buildChatId()` elegantly avoids any pairing race — both devices compute the same chat document path independently.
- `BaseActivity`'s centralization of the app-lock timer reset is a clean architectural fix for what was previously a per-screen, easy-to-forget concern.

---

## 5. Prioritized Recommendations

1. **(High)** Fix `MessageBuilder` to centralize the "no key → abort" check and add the missing `"id"` field — this currently causes forwarded messages and notification-reply messages to vanish (§3.1).
2. **(High)** Stop persisting `"[Waiting for encryption key…]"` / `"[Decryption failed]"` placeholders to Room, and add a retry-decrypt pass when the shared key becomes available (§3.4) — **this is the most likely direct cause of the "decryption system keeps failing" symptom**.
3. **(High)** Rotate the exposed Firebase API key and purge `google-services.json` from the repo/history (§3.2).
4. **(High)** Harden `SecurePrefs` against silent plaintext fallback (§3.3) — both a security and a "permanent decryption failure" risk.
5. **(Medium)** Fix repeating "Today/Yesterday" date headers by wiring up the unused `DateHeaderHelper.needsHeader()` for header placement and decoupling it from `getLabel()`'s now-relative text (§3.7).
6. **(Low/Medium)** Backfill `partnerName_<uid>` for pre-existing pairs (§3.8); reconsider decoy-flow messaging (§3.5); remove the dead legacy AES key (§3.6).
