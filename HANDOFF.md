# DuoShield — Full Build Handoff

## What This Project Is
A native Android (Java) end-to-end encrypted secure messaging app.
- Package: `com.duoshield.app`
- Firebase project: `duoshield-8caf1`
- Room DB version: **7** (next change = v8 + MIGRATION_7_8)
- minSdk 26, targetSdk 34, Gradle 8.2.0, AGP 8.2.0 (requires JDK 17 to build)

---

## GitHub / CI

- Repo: `https://github.com/kumarclaude4-hash/DuoShield-`
- CI: GitHub Actions — `.github/workflows/build.yml`
  - **lint** + **build debug APK** on every push/PR to main/master
  - **signed release APK** on `v*` tags
- Required GitHub Actions secret: **`GOOGLE_SERVICES_JSON`** — paste the full contents of `app/google-services.json`
- Optional release secrets: `KEYSTORE_BASE64`, `KEY_ALIAS`, `KEY_PASSWORD`, `STORE_PASSWORD`

---

## Current State

### Java Files — root package `com.duoshield.app`
| File | Purpose |
|------|---------|
| `BaseActivity.java` | Lock-screen check in `onStart()` / background timer in `onPause()`. Screenshots **enabled** (FLAG_SECURE removed). |
| `ChatMediaActivity.java` | Main chat — messages, voice, media, pinning, reactions, disappearing messages |
| `ConversationListActivity.java` | Conversation list with search |
| `DuoShieldApp.java` | Application class — notification channels, AppLock init |
| `FakeChatsActivity.java` | Decoy screen shown on wrong PIN (plausible deniability) |
| `FullScreenImageActivity.java` | Pinch-zoom image viewer (PhotoView) |
| `KeyFingerprintActivity.java` | Shows ECDH public key fingerprints for out-of-band verification |
| `LockScreenActivity.java` | PIN + biometric gate; PBKDF2 on bg thread; brute-force backoff + wipe |
| `MainActivity.java` | Routing trampoline: signed-in → route(), else → SignInActivity |
| `MediaViewerActivity.java` | Video playback |
| `MessageSearchActivity.java` | In-conversation full-text search |
| `SignInActivity.java` | **LAUNCHER** — email/password Firebase Auth |

### `crypto/`
| File | Purpose |
|------|---------|
| `CryptoHelper.java` | AES-GCM encrypt/decrypt |
| `CryptoInitializer.java` | EC keypair + ECDH shared AES key in EncryptedSharedPreferences. `getSharedKey(ctx)` → `SecretKey` |
| `ECDHHelper.java` | Raw ECDH key exchange + HKDF derivation |
| `KeyManager.java` | AndroidKeyStore AES key (fallback only — not used for message encryption) |

### `db/`
| File | Purpose |
|------|---------|
| `AppDatabase.java` | Room DB v7, migrations 1→7. Singleton `getInstance(ctx)` |
| `MessageDao.java` | Full CRUD + `deleteExpired(currentTime)` — pass `System.currentTimeMillis()` |
| `SelfDestructWorker.java` | WorkManager Worker — deletes expired messages every 15 min |

### `firebase/`
| File | Purpose |
|------|---------|
| `FirestoreHelper.java` | Firestore CRUD helpers |
| `MediaHelper.java` | Storage upload/download for images |
| `VoiceNoteHelper.java` | Storage upload/download for voice notes |

### `models/`
| File | Key Fields |
|------|-----------|
| `Message.java` | id, conversationId, sender, text, mediaUrl, mediaType, timestamp, status, edited, expiresAt, replyToId, replyPreview, reaction |
| `Conversation.java` | id, partnerUid, partnerName, avatarUrl, lastMessage, lastMessageTs, unreadCount, isTyping, isOnline, isMuted |

### `notifications/`
| File | Purpose |
|------|---------|
| `DuoShieldMessagingService.java` | FCM service — receives push, builds notification |
| `MarkReadReceiver.java` | Notification action — marks conversation read |
| `MessageReplyReceiver.java` | Inline reply from notification; checks ECDH key before sending |
| `NotificationHelper.java` | Creates notification channel |
| `NotificationStyler.java` | Builds rich notifications with reply/mark-read actions |

### `pairing/`
| File | Purpose |
|------|---------|
| `PairingManager.java` | Connect by DS-XXXXXXXX User ID; ECDH handshake via Firestore; deterministic chatId via SHA-256 |

### `security/`
| File | Purpose |
|------|---------|
| `BiometricHelper.java` | BiometricPrompt wrapper — BIOMETRIC_STRONG only, no device credential |
| `DuressManager.java` | PBKDF2 duress PIN; `triggerDuress()` wipes SecurePrefs → Room → plain prefs → Firestore |

### `ui/`
| File | Purpose |
|------|---------|
| `ConversationAdapter.java` | RecyclerView + DiffUtil. `setConversations(list)` |
| `MessageAdapter.java` | RecyclerView + DiffUtil. `setMessages(list)`. Constructor: `(List<Message>, String myUid, OnVoicePlayListener, OnMessageLongPressListener)` |
| `PairingActivity.java` | UI for pairing flow |
| `SettingsActivity.java` | PIN, biometric, duress PIN, disappearing messages timer, auto-delete, unpair |
| `SwipeToDeleteCallback.java` | Left-swipe delete |
| `SwipeToReplyCallback.java` | Right-swipe reply |

### `util/` — key helpers
| File | One-liner |
|------|----------|
| `AppLockManager.java` | `shouldLock(ctx)` after 3 min background — only if PIN is set |
| `ConversationMetaUpdater.java` | Updates Firestore `lastMessage` (encrypted), `lastMessageTs`, `unread_uid`. Skips `lastMessage` field when encryption returns null |
| `EncryptionHelper.java` | `encrypt(ctx, text)` → null on failure (never plaintext). `decrypt(ctx, ct)` → raw ct on failure |
| `FirebaseCostGuard.java` | **Singleton** — `getInstance(ctx)`. Daily quota guard with persistent `warned_*` flags |
| `FirebaseQuotaSummary.java` | `get(ctx)` → formatted quota string using `getInstance()` |
| `ForwardMessageHelper.java` | Message forwarding stub |
| `MessageBuilder.java` | `sendTextMessage(ctx, convId, myUid, partnerUid, text, replyToId, replyToText)` |
| `PinManager.java` | PBKDF2 (310k iters, SHA-256, 16-byte salt) in SecurePrefs. `hex(salt):hex(hash)` format |
| `SecurePrefs.java` | EncryptedSharedPreferences wrapper — file `duoshield_secure_prefs` |
| `WipeHelper.java` | `wipeAll(ctx)` — SecurePrefs → Room DB file → plain prefs → cache → SignIn |

---

## Layouts — 14 files

| File | Key View IDs |
|------|-------------|
| `activity_sign_in.xml` | `tilEmail`, `tilPassword`, `btnAction`, `tvToggleMode` |
| `activity_conversation_list.xml` | `recyclerConversations`, `tvEmpty`, `searchBar`, `etSearch` |
| `activity_chat_media.xml` | `messageRecycler`, `messageInput`, `sendButton`, `micButton`, `uploadButton`, `replyPreviewBar`, `pinnedBanner`, `disappearTimerBanner` |
| `activity_lock_screen.xml` | `etPin`, `tvError`, `btnUnlock`, `btnBiometric` |
| `activity_settings.xml` | `switchNotifications`, `switchSelfDestruct`, `switchBiometric`, `switchDarkMode`, `btnDisappearing`, `tvDisappearSub`, `etNewPin`, `etConfirmPin`, `btnSetPin`, `btnClearPin`, `etDuressPin`, `btnSetDuressPin`, `btnUnpair` |
| `activity_pairing.xml` | Pairing code entry / display |
| `activity_fake_chats.xml` | Decoy conversation list |
| `activity_key_fingerprint.xml` | `tv_my_fingerprint`, `tv_partner_fingerprint`, `btn_share` |
| `activity_message_search.xml` | `sv_search`, `progress`, `rv_results`, `tv_empty` |
| `activity_full_screen_image.xml` | `com.github.chrisbanes.photoview.PhotoView` |
| `item_message.xml` | `messageBubble`, `messageText`, `messageImage`, `tickIcon`, `editedLabel`, `messageTimestamp`, `reactionText`, `replyPreviewContainer` |
| `item_conversation.xml` | `iv_avatar`, `tv_name`, `tv_preview`, `tv_time`, `tv_badge` |
| `item_decoy_conversation.xml` | Decoy item for FakeChatsActivity |
| `item_search_result.xml` | `tv_sender`, `tv_time`, `tv_text` |

---

## SharedPreferences Reference

### `duoshield_prefs` (plain, MODE_PRIVATE)
| Key | Type | Purpose |
|-----|------|---------|
| `my_uid` | String | Firebase Auth UID |
| `partner_uid` | String | Partner's Firebase UID |
| `conversation_id` | String | Active chat Firestore doc ID |
| `is_paired` | boolean | ECDH pairing complete |
| `biometric_enabled` | boolean | Biometric lock on/off |
| `app_lock_bg_ts` | long | Epoch ms when app was last backgrounded |
| `fcm_token` | String | FCM registration token |
| `disappear_ms` | long | Disappearing messages timer (0 = off) |
| `self_destruct_enabled` | boolean | Auto-delete switch |
| `self_destruct_minutes` | long | Auto-delete TTL in minutes |
| `pin_fail_count` | int | Wrong PIN attempt counter |
| `dark_mode` | boolean | Dark mode toggle |

### `duoshield_secure_prefs` (EncryptedSharedPreferences via SecurePrefs)
| Key | Type | Purpose |
|-----|------|---------|
| `KEY_EC_PUBLIC` | String | Own EC public key (Base64) |
| `KEY_EC_PRIVATE` | String | Own EC private key (Base64) |
| `KEY_SHARED_AES` | String | ECDH-derived AES-256 key (Base64) |
| `KEY_PARTNER_EC_PUBLIC` | String | Partner EC public key (Base64) |
| `app_pin_hash` | String | PBKDF2 PIN hash (`hexSalt:hexHash`) |
| `duress_pin_hash` | String | PBKDF2 duress PIN hash (`hexSalt:hexHash`) |

### `duoshield_cost_guard` (plain, FirebaseCostGuard)
| Key | Type | Purpose |
|-----|------|---------|
| `day_key` | String | Current day epoch-day number |
| `reads` / `writes` / `deletes` | int | Daily counters |
| `warned_reads` / `warned_writes` / `warned_deletes` | boolean | 80% warning fired flags (reset on rollover) |

---

## Firestore Schema

```
/chats/{chatId}                         chatId = SHA-256(smallerUid + "/" + largerUid)
  ├── participants: [uid, uid]
  ├── partnerName_<uid>: string          each side writes the OTHER person's name
  ├── lastMessage: string (encrypted)
  ├── lastMessageTs: Timestamp
  ├── lastSenderId: string
  ├── unread_<uid>: number
  ├── typing_<uid>: boolean
  ├── online_<uid>: boolean
  ├── lastSeen_<uid>: Timestamp
  ├── muted_<uid>: boolean
  └── /messages/{msgId}
        ├── id, conversationId, sender
        ├── text: string (AES-256-GCM encrypted)
        ├── type: "text"|"image"|"video"|"voice"|"contact_card"
        ├── path: string (Storage path)
        ├── isEncrypted: boolean
        ├── timestamp: Timestamp
        ├── status: "pending"|"sent"|"delivered"|"read"
        ├── edited: boolean
        ├── expiresAt: long (epoch ms, 0 = no expiry)
        ├── replyToId, replyToText: string
        └── reaction: string (emoji)

/users/{uid}
  ├── fcmToken: string
  ├── ecPublicKey: string (Base64)
  └── displayName: string

/identities/{DS-XXXXXXXX}
  └── uid: string                        maps DuoShield User ID → Firebase UID
```

---

## Strict Rules (must never be broken)
1. **FirebaseCostGuard** — use `FirebaseCostGuard.getInstance(ctx)` (never `new`). Call `canRead/Write/Delete(n)` + `recordReads/Writes/Deletes(n)` around every Firestore operation.
2. **One Firestore listener per screen** — attach in `onStart()`, detach in `onStop()`. Null-guard before attaching.
3. **Batch deletes** — only via `WriteBatch` (max 450 ops) or `WorkManager`. Never loop-delete.
4. **DiffUtil always** — `adapter.setMessages(list)` / `adapter.setConversations(list)`. Never `notifyDataSetChanged()`.
5. **No Cloud Functions** — all logic is client-side.
6. **Room DB version 7** — any schema change needs `MIGRATION_X_Y` in `AppDatabase.java`. Never `fallbackToDestructiveMigration()`.
7. **ECDH key only for encryption** — `EncryptionHelper.encrypt()` returns null if key absent; callers must not fall back to plaintext.
8. **No BiometricHelper in ChatMediaActivity.onCreate()** — lock handled exclusively by `BaseActivity.onStart()`.

---

## Status Tick Logic
```
pending   → ic_done       tint #9E9E9E   (single grey tick)
sent      → ic_done_all   tint #90A4AE   (double grey tick)
delivered → ic_done_all   tint #90A4AE   (double grey tick)
read      → ic_done_all   tint #2AABB8   (double teal tick)
```
Implemented in `MessageStatusHelper.bind(tickIcon, msg, myUid)`.

---

## Build Instructions

```bash
# Prerequisites
# 1. Place app/google-services.json (from Firebase console, project duoshield-8caf1)
# 2. Place app/src/main/assets/service-account.json (Firebase → Project Settings → Service accounts)

./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

./gradlew assembleRelease   # requires signing config
./gradlew lint
```

---

## What Still Needs To Be Done

- [ ] **Message forwarding** — action sheet item exists, `ForwardMessageHelper` is a stub
- [ ] **Image/video encryption before upload** — files go to Firebase Storage in plaintext
- [ ] **Unread badge** on launcher icon (Android O+)
- [ ] **`network_security_config.xml`** for HTTP link preview URLs (cleartext policy)
- [ ] **Unit tests** — `app/src/test/` and `app/src/androidTest/` are empty
- [ ] **Play Store hardening** — signing config, `debuggable false`, bump `versionCode`
- [ ] **Group conversations** — requires Firestore schema redesign (currently 1-to-1 only)

---

## Completed in This Build Session ✅

### Bug fixes (20 total — all applied)
- **FirebaseCostGuard** → private singleton with `getInstance(ctx)`; persistent `warned_*` flags; daily rollover resets counters and flags
- **FirebaseQuotaSummary** → updated to use `getInstance()` (was the sole compile error breaking the build)
- **ECDH race condition** → `keyPending` volatile flag; 8-second watchdog; executor `finally` restarts listener
- **`partnerUid` null at startup** → reload from prefs; Toast + return if still null
- **Notification permission race (Android 13+)** → `requestPermissions()` + `return`; `onRequestPermissionsResult()` → `proceedAfterPermission()`
- **Plaintext notification reply** → checks `CryptoInitializer.getSharedKey()` before send
- **PIN hash in plain prefs** → PBKDF2WithHmacSHA256, 310k iters, SecurePrefs, `hexSalt:hexHash`, constant-time compare
- **Duress PIN hash in plain prefs** → same PBKDF2 scheme in SecurePrefs
- **Biometric triggers lock without PIN** → `shouldLock()` returns false if no PIN set
- **Lock timer reset on in-app navigation** → `onAppForegrounded()` in `else` branch of `BaseActivity.onStart()`
- **WipeHelper skips SecurePrefs** → wipe order: SecurePrefs → Room DB file → plain prefs → cache
- **triggerDuress skips SecurePrefs** → same wipe order
- **Wrong key fallback in EncryptionHelper** → ECDH-only; returns null when key absent
- **Pairing succeeds without shared key** → `onError()` when retries exhausted with null key
- **deleteExpired wrong cutoff** → `deleteExpired(System.currentTimeMillis())`
- **No PIN brute-force protection** → 5-attempt backoff (30s × excess); 10-attempt silent wipe; PBKDF2 on background thread
- **`partnerName` never written** → `finalizeConnection()` writes `partnerName_<uid>` with `SetOptions.merge()`
- **google-services.json in repo** → added to `.gitignore`; template created
- **Silent plaintext fallback** → `encrypt()` returns null on failure; `ConversationMetaUpdater` skips `lastMessage` field when null

### Disappearing messages UI (Settings)
- Added "1 week" option; dialog pre-selects current value; `tvDisappearSub` updates live
- `scheduleOrCancelDestruct()` wires WorkManager on every change

### GitHub Actions CI
- Lint + debug APK build on every push; signed release APK on `v*` tags
- `GOOGLE_SERVICES_JSON` secret required; stub `service-account.json` created at build time
- Gradle cache for fast subsequent runs

### Screenshots enabled
- Removed `FLAG_SECURE` from `BaseActivity`, `LockScreenActivity`, and `SignInActivity`

### Codebase analysis fixes (§3.1 – §3.8 from external audit)

**§3.1 — MessageBuilder hardened (HIGH)**
- Added missing `"id"` field to Firestore document — listenForMessages() was silently skipping all messages sent via MessageBuilder (notification replies, forwarded messages) because it gates on `id != null`
- Centralized "no key → abort" check inside `MessageBuilder.sendTextMessage()` — if ECDH key is null or `CryptoHelper.encrypt()` returns null, the message is NOT sent rather than sent as plaintext. ForwardMessageHelper and MessageReplyReceiver now both get this protection automatically.

**§3.3 — SecurePrefs silent plaintext fallback removed (HIGH)**
- `SecurePrefs.get()` now caches its result (thread-safe singleton) and tracks `initialized` + `encryptionAvailable` flags
- On `EncryptedSharedPreferences` failure, logs prominently and sets `encryptionAvailable = false`; plaintext fallback retained to keep app alive but crypto material is treated as absent
- Added `SecurePrefs.isAvailable()` method; `SecurePrefs.reset()` for WipeHelper
- `CryptoInitializer.getSharedKey()` and `getMyPrivateKey()` now return `null` if `!SecurePrefs.isAvailable()`, forcing re-pairing instead of silently reading plaintext-stored key material

**§3.4 — Placeholder strings no longer persisted to Room (HIGH — root cause of "decryption keeps failing")**
- Added `pendingDecryptQueue` (`List<Message>`) to `ChatMediaActivity` — stores messages with raw ciphertext (isEncrypted=true) that arrive before the ECDH key is ready
- When key is null at receive time: placeholder shown in UI, raw ciphertext queued, NO Room write
- Added `retryPendingDecryption()` — called from all success paths in `reEnsureEcdhKey()` — decrypts queued messages, updates adapter in-place, and saves properly-decrypted Message to Room
- `[Decryption failed]` messages also suppressed from Room (shouldPersist = false)

**§3.5 — Decoy flow backoff no longer self-reveals (LOW/MEDIUM)**
- `handleWrongPin()`: replaced `"Too many attempts. Wait N s…"` countdown with generic `"Incorrect PIN"` message; delay still applied silently so backoff logic is invisible to attacker

**§3.6 — Dead legacy AES key removed (LOW)**
- `CryptoInitializer.ensureKeyExists()` and `ensureKeyExists(Context)` no longer generate the AndroidKeyStore AES-256-GCM key (`duoshield_key`) — it was orphaned by the Bug 14 fix (EncryptionHelper now ECDH-only) and only added an unused Keystore entry per install

**§3.7 — Repeating date headers fixed (MEDIUM)**
- `MessageAdapter.rebuildDisplay()`: replaced `DateHeaderHelper.getLabel()`-based placement with `DateHeaderHelper.needsHeader(prevTs, currentTs)` — which compares two absolute timestamps (not relative to "now") — to decide where headers go; `getLabel()` still used only for formatting the header text. Fixes the "Today/Yesterday/Today/Yesterday…" bug that appeared whenever the adapter was refreshed after midnight.

**§3.8 — Stale "Partner" name backfill (LOW)**
- `ConversationListActivity.listenForConversation()`: when `partnerName_<myUid>` is missing from the chat doc (pre-fix pairs), now reads `users/{partnerUid}.displayName` and writes it back so subsequent snapshots show the real name

**§3.2 — URGENT ops action required (cannot be done in code)**
- The Firebase API key `AIzaSyB80HYbZqkgBgGTp9TUkQkJLFrAAfgquhY` (project `duoshield-8caf1`) was present in the exported archive
- **Action: Rotate this key immediately** in Google Cloud Console → APIs & Services → Credentials → restrict or regenerate the Android API key
- Purge `app/google-services.json` from git history if it was ever committed (use `git filter-repo` or BFG Repo Cleaner), then re-add only the `.template`
- Verify Firestore security rules enforce `request.auth != null` and per-chat access

---

## To Continue Building on Another Account
1. Clone: `git clone https://github.com/kumarclaude4-hash/DuoShield-`
2. Paste this entire `HANDOFF.md` as the first message
3. Tell the agent: **"Continue building DuoShield from the handoff. Pick up from 'What Still Needs To Be Done'."**
