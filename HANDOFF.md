# DuoShield — Full Build Handoff

## What This Project Is
A native Android (Java) end-to-end encrypted secure messaging app.
- Package: `com.duoshield.app`
- Firebase project: `duoshield-8caf1`
- Room DB version: **7** (next change = v8 + MIGRATION_7_8)
- minSdk 26, targetSdk 34, Gradle 8.2.0

---

## GitHub Authentication Issue
The push failed because the stored GitHub token expired.
To fix: go to **GitHub → Settings → Developer Settings → Personal Access Tokens** and generate a new one with `repo` scope, then update the Replit GitHub integration.

Once authenticated, run:
```
git push origin main
```

---

## Current State (as of this handoff)

### Java Files — 85 total

#### Root package `com.duoshield.app`
| File | Purpose |
|------|---------|
| `BaseActivity.java` | Sets FLAG_SECURE, handles lock-screen check in onResume |
| `ChatMediaActivity.java` | Main chat screen — messages, voice, media, pinning, reactions |
| `ConversationListActivity.java` | Conversation list with unread badges, swipe-to-delete |
| `DuoShieldApp.java` | Application class — inits channels, AppLock, SelfDestructScheduler |
| `FullScreenImageActivity.java` | Pinch-zoom image viewer (PhotoView) |
| `KeyFingerprintActivity.java` | Shows ECDH public key fingerprints |
| `LockScreenActivity.java` | PIN + biometric gate (shows on app resume) |
| `MainActivity.java` | Routing trampoline: signed-in→route(), else→SignInActivity |
| `MediaViewerActivity.java` | Video playback |
| `MessageSearchActivity.java` | In-conversation full-text search |
| `SearchResultsAdapter.java` | RecyclerView adapter for search results |
| `SignInActivity.java` | **LAUNCHER** — email/password Firebase Auth |

#### `crypto/`
| File | Purpose |
|------|---------|
| `CryptoHelper.java` | AES-GCM encrypt/decrypt (`encrypt(text, key)`, `decrypt(ciphertext, key)`) |
| `CryptoInitializer.java` | Manages EC keypair + shared AES key in SharedPreferences. `getSharedKey(ctx)` returns `SecretKey`. |
| `ECDHHelper.java` | Raw ECDH key exchange |
| `KeyManager.java` | AndroidKeyStore AES key (`getKey()` returns `SecretKey`) |

#### `db/`
| File | Purpose |
|------|---------|
| `AppDatabase.java` | Room DB v7, migrations 1→7. Singleton `getInstance(ctx)`. |
| `MessageDao.java` | `getMessages(convId)`, `getMessagesPage(convId, limit, offset)`, `searchMessages(convId, query)`, `insert(msg)`, `updateStatus(id, status)`, `deleteAll(convId)` |
| `SelfDestructWorker.java` | WorkManager Worker — deletes expired messages |

#### `firebase/`
| File | Purpose |
|------|---------|
| `FirestoreHelper.java` | Firestore CRUD helpers |
| `MediaHelper.java` | Storage upload/download for images |
| `VoiceNoteHelper.java` | Storage upload/download for voice notes |

#### `models/`
| File | Key Fields |
|------|-----------|
| `Message.java` | id, conversationId, sender, text, mediaUrl, mediaType, timestamp, status (`pending`/`sent`/`delivered`/`read`), edited (boolean), expiresAt, replyToId, replyPreview, reaction |
| `Conversation.java` | id, partnerUid, partnerName, partnerPhotoUrl, lastMessage, lastMessageTs, unreadCount |

#### `notifications/`
| File | Purpose |
|------|---------|
| `DuoShieldMessagingService.java` | FCM service — receives push |
| `MarkReadReceiver.java` | Notification action receiver. Constants: `EXTRA_CONV_ID`, `EXTRA_MY_UID` |
| `MessageReplyReceiver.java` | Notification reply receiver. Constants: `KEY_REPLY_TEXT`, `EXTRA_CONV_ID`, `EXTRA_MY_UID` |
| `NotificationHelper.java` | Thin delegate to NotificationStyler |
| `NotificationStyler.java` | Creates channels + builds rich notifications with reply/mark-read actions |

#### `pairing/`
| File | Purpose |
|------|---------|
| `PairingManager.java` | ECDH device pairing with Firestore handshake |

#### `security/`
| File | Purpose |
|------|---------|
| `BiometricHelper.java` | BiometricPrompt wrapper |
| `DuressManager.java` | Duress PIN detection → triggers WipeHelper |

#### `ui/`
| File | Purpose |
|------|---------|
| `ConversationAdapter.java` | RecyclerView adapter with DiffUtil + `setConversations(list)` |
| `MessageAdapter.java` | RecyclerView adapter with DiffUtil + **`setMessages(list)`** (NOT notifyDataSetChanged). Constructor: `(List<Message>, String myUid, OnVoicePlayListener, OnMessageLongPressListener)` |
| `PairingActivity.java` | UI for ECDH pairing flow |
| `SettingsActivity.java` | App settings (biometric, PIN, self-destruct timer, export, wipe) |
| `SwipeToDeleteCallback.java` | ItemTouchHelper for left-swipe delete |
| `SwipeToReplyCallback.java` | ItemTouchHelper for right-swipe reply |

#### `util/` — 38 helpers
| File | One-liner |
|------|----------|
| `AppLockManager.java` | Tracks background timestamp, `shouldLock(ctx)` after 3min |
| `AppUpdateHelper.java` | `getVersionName(ctx)` |
| `ClipboardHelper.java` | Copy text + auto-clear after 90s |
| `ConversationMetaUpdater.java` | Updates Firestore conversation lastMessage + unread counter |
| `DateHeaderHelper.java` | `getLabel(ts)` → "Today"/"Yesterday"/day name/date; `needsHeader(prev, current)` |
| `DeliveryReceiptHelper.java` | Batch-updates messages to `delivered` status |
| `EditMessageHelper.java` | `canEdit(ts, sender, myUid)` (48h window); `editMessage(convId, msgId, text)` |
| `EncryptionHelper.java` | Thin wrap of CryptoHelper — `encrypt(ctx, text)`, `decrypt(ctx, ciphertext)` |
| `ExportHelper.java` | Export chat to PDF via android.graphics.pdf |
| `FcmTokenHelper.java` | Registers FCM token to Firestore users collection |
| `FirebaseCostGuard.java` | Daily quota guard — `canRead/Write/Delete(n)`, `recordReads/Writes/Deletes(n)` |
| `FirebaseQuotaSummary.java` | `get(ctx)` → formatted quota string |
| `ForwardMessageHelper.java` | Forwards a message to another conversation |
| `GlideHelper.java` | `loadAvatar(ctx, url, iv)`, `loadMedia(ctx, url, iv)` |
| `HapticHelper.java` | `wrongPin(ctx)`, `longPress(ctx)`, `send(ctx)`, `reaction(ctx)` |
| `ImageCacheHelper.java` | Trim Glide cache to 50 MB |
| `KeyboardHelper.java` | `show(view)`, `hide(view)` |
| `LastSeenFormatter.java` | `format(epochMs, isOnline)` |
| `LinkPreviewHelper.java` | `extractFirstUrl(text)`, `containsUrl(text)` |
| `MediaPickerHelper.java` | Activity Result launchers for image/video picks |
| `MediaSizeEstimator.java` | `getCacheSizeLabel(ctx)` |
| `MessageBuilder.java` | `sendTextMessage(ctx, convId, myUid, partnerUid, text, replyToId, replyToText)` |
| `MessagePaginator.java` | `loadMore(ctx, convId, cb)` — 30-item pages, local Room |
| `MessageStatusHelper.java` | `bind(tickIcon, msg, myUid)` — sets correct tick drawable + tint |
| `MuteHelper.java` | `mute(convId, myUid, muted)` |
| `NetworkStateHelper.java` | `isOnline(ctx)`, `updateBanner(ctx, bannerView)` |
| `OnlinePresenceHelper.java` | `setOnline/setOffline(convId, myUid)` → Firestore |
| `PinManager.java` | SHA-256 PIN store/verify/clear |
| `PinMessageHelper.java` | `pin/unpin` messages in Firestore conversation doc |
| `PresenceThrottle.java` | Debounced `setTyping(bool)` → writes typing_ field to Firestore |
| `ReactMessageHelper.java` | `react(convId, msgId, emoji, myUid)` |
| `ReadReceiptHelper.java` | `markAllRead(convId, myUid)`, `markMessageRead(convId, msgId)` |
| `SearchHelper.java` | `runSearch(ctx, convId, query, cb)` on background thread |
| `SecureShareHelper.java` | Downloads image and shares via FileProvider |
| `SelfDestructScheduler.java` | Schedules/cancels periodic WorkManager SelfDestructWorker |
| `SessionLockTimer.java` | 3-min inactivity → launches LockScreenActivity |
| `StorageCleanupWorker.java` | WorkManager Worker — trims cache dir to 50 MB |
| `TextStyleHelper.java` | `apply(tv, text)` — applies *bold*, _italic_, ~strike~, `mono` |
| `TimeFormatter.java` | `format(epochMs)` → "HH:mm"/"Yesterday"/"Mon"/date |
| `TypingThrottle.java` | Debounce key strokes → triggers typing listener |
| `UnreadCountHelper.java` | `reset(convId, myUid)` → Firestore unread_uid = 0 |
| `UploadHelper.java` | `uploadImage(ctx, uri, convId, cb)`, `uploadVoice(ctx, path, convId, cb)` |
| `VoiceMessagePlayer.java` | `play(url, cb)`, `pause()`, `resume()`, `release()` |
| `VoiceRecorderHelper.java` | `start(ctx, cb)`, `stop()`, `cancel()` with amplitude tracking |
| `WipeHelper.java` | `wipeAll(ctx)` — clears DB, prefs, cache, relaunches SignIn |

#### `viewmodel/`
| File | Purpose |
|------|---------|
| `ConversationViewModel.java` | LiveData for conversations list |
| `MessageViewModel.java` | LiveData for messages in a conversation |

---

## Layouts — 14 files

| File | Used By |
|------|---------|
| `activity_sign_in.xml` | SignInActivity — email/password form |
| `activity_conversation_list.xml` | ConversationListActivity — IDs: `toolbar`, `recyclerConversations`, `tvEmpty`, `etSearch` |
| `activity_chat_media.xml` | ChatMediaActivity |
| `activity_chat.xml` | (legacy) |
| `activity_lock_screen.xml` | LockScreenActivity — IDs: `etPin`, `tvError`, `btnUnlock`, `btnBiometric` |
| `activity_message_search.xml` | MessageSearchActivity — IDs: `toolbar`, `sv_search`, `progress`, `rv_results`, `tv_empty` |
| `activity_key_fingerprint.xml` | KeyFingerprintActivity — IDs: `toolbar`, `tv_my_fingerprint`, `tv_partner_fingerprint`, `btn_share` |
| `activity_full_screen_image.xml` | FullScreenImageActivity — uses `com.github.chrisbanes.photoview.PhotoView` |
| `activity_media_viewer.xml` | MediaViewerActivity |
| `activity_pairing.xml` | PairingActivity |
| `activity_settings.xml` | SettingsActivity |
| `item_message.xml` | MessageAdapter — IDs: `messageBubble`, `messageText`, `messageImage`, `videoContainer`, `tickIcon`, `editedLabel`, `messageTimestamp`, `reactionText`, `replyPreviewContainer`, `pinIndicator`, etc. |
| `item_conversation.xml` | ConversationAdapter — IDs: `iv_avatar`, `tv_name`, `tv_preview`, `tv_time`, `tv_badge` |
| `item_search_result.xml` | SearchResultsAdapter — IDs: `tv_sender`, `tv_time`, `tv_text` |

---

## Drawables — 38 files

**Vector icons:** `ic_arrow_back`, `ic_attach`, `ic_camera`, `ic_close`, `ic_copy`, `ic_delete`, `ic_done`, `ic_done_all`, `ic_edit`, `ic_emoji`, `ic_fingerprint`, `ic_forward`, `ic_image`, `ic_location`, `ic_lock`, `ic_mic`, `ic_more_vert`, `ic_person`, `ic_pin`, `ic_play_video`, `ic_reply`, `ic_search`, `ic_send`, `ic_settings`, `ic_shield`, `ic_stop`, `ic_tick_double`, `ic_tick_double_blue`, `ic_tick_single`, `ic_timer`, `ic_videocam`

**Backgrounds:** `bg_badge`, `bg_bubble_mine`, `bg_bubble_theirs`, `bg_input_field`, `bg_pin_indicator`

**PNG:** `ic_secure.png`

---

## Resource Files

### `values/`
- `colors.xml` — full dark theme: bg `#0D0D0D`, surface `#1E1E1E`, sent bubble `#1A5C6E`, accent `#2AABB8`
- `colors_bubbles.xml` — intentionally empty (duplicates removed, lives in colors.xml)
- `strings.xml` — 40+ strings covering all screens
- `themes.xml` — `Theme.DuoShield` extends `Theme.MaterialComponents.DayNight.NoActionBar`; `Theme.DuoShield.FullScreen`; `ShapeAppearanceOverlay.CircleAvatar`

### `menu/`
- `chat_menu.xml` — IDs: `action_settings`, `action_wallpaper`
- `conversation_list_menu.xml` — IDs: `action_search`, `action_settings`, `action_key_fingerprint`, `menu_quota`, `menu_export`, `menu_wipe`

### `xml/`
- `file_paths.xml` — FileProvider paths (cache, files, external-files)

### `anim/`
- (empty — animations not yet written)

---

## `app/build.gradle` — Key Dependencies
```groovy
// Biometric (alpha for full BiometricPrompt)
androidx.biometric:biometric:1.2.0-alpha05

// Security — EncryptedSharedPreferences
androidx.security:security-crypto:1.1.0-alpha06

// Room v2.6.1 + annotationProcessor

// Lifecycle ViewModels + LiveData v2.7.0

// Firebase BOM 32.7.0 (auth, firestore, storage, messaging)

// WorkManager 2.9.0

// Material 1.11.0

// Glide 4.16.0

// PhotoView 2.3.0 (JitPack — already in root build.gradle)

// google-auth-library-oauth2-http:1.23.0

// GridLayout 1.0.0
```

---

## AndroidManifest.xml — Declared Components
```
LAUNCHER:  .SignInActivity
           .MainActivity         (routing trampoline)
           .ConversationListActivity
           .ChatMediaActivity
           .LockScreenActivity
           .ui.PairingActivity
           .ui.SettingsActivity
           .KeyFingerprintActivity
           .FullScreenImageActivity
           .MediaViewerActivity
           .MessageSearchActivity
SERVICE:   .notifications.DuoShieldMessagingService (FCM)
RECEIVERS: .notifications.MarkReadReceiver
           .notifications.MessageReplyReceiver
PROVIDER:  androidx.core.content.FileProvider (@xml/file_paths)
APPLICATION android:name=".DuoShieldApp"
```

---

## Strict Rules (must never be broken)
1. **`FLAG_SECURE` in ALL activities** — `BaseActivity` handles it via `getWindow().setFlags(FLAG_SECURE, FLAG_SECURE)`. Every activity MUST extend `BaseActivity` (except `LockScreenActivity` and `SignInActivity` which set it manually).
2. **FirebaseCostGuard** — call `guard.canRead/Write/Delete(n)` and `guard.recordReads/Writes/Deletes(n)` before every Firestore operation.
3. **One Firestore listener per screen** — attach in `onStart()`, detach in `onStop()`. Store as `ListenerRegistration`.
4. **Batch deletes** — only via `WriteBatch` (max 450 ops per batch) or `WorkManager`. Never delete in a loop.
5. **DiffUtil** — always use `adapter.setMessages(list)` / `adapter.setConversations(list)`. Never `notifyDataSetChanged()`.
6. **No Cloud Functions** — all logic is client-side (Firestore security rules enforce access).
7. **Room DB version** — currently **7**. Any schema change needs MIGRATION_X_Y added to `AppDatabase.java`.

---

## Status Tick Logic
```
pending   → R.drawable.ic_done      tint #9E9E9E  (single grey tick)
sent      → R.drawable.ic_done_all  tint #90A4AE  (double grey tick)
delivered → R.drawable.ic_done_all  tint #90A4AE  (double grey tick)
read      → R.drawable.ic_done_all  tint #2AABB8  (double teal tick)
```
Implemented in `MessageStatusHelper.bind(tickIcon, msg, myUid)`.

---

## What Still Needs To Be Done

### Critical (can't compile without this)
1. **`google-services.json`** — must be placed at `app/google-services.json`. Download from Firebase console for project `duoshield-8caf1`. This file is never committed to git.

### Remaining Features
2. **Voice Note waveform UI** — `ChatMediaActivity` calls `VoiceRecorderHelper.start()` and `VoiceMessagePlayer.play()` but the waveform amplitude visualizer view in the layout needs wiring.
3. **Launcher badge count** — unread count badge on app icon via NotificationCompat badges (Android O+).
4. **Unit tests** — `app/src/test/` and `app/src/androidTest/` are empty.

---

## Completed in This Session ✅

### Wrong-PIN shake animation
- **`res/anim/shake.xml`** + **`res/anim/shake_interpolator.xml`** — `cycleInterpolator` (3 cycles, 500 ms) drives a horizontal translate on the PIN `EditText` every time a wrong PIN is entered.
- **`LockScreenActivity.java`** — `AnimationUtils.loadAnimation(this, R.anim.shake)` + `etPin.startAnimation(shake)` called immediately after `HapticHelper.wrongPin()`.

### Security / PIN / Duress fixes
- **`BiometricHelper.java`** — removed `DEVICE_CREDENTIAL` from allowed authenticators; now uses `BIOMETRIC_STRONG` only with `setNegativeButtonText("Use PIN instead")`. Phone's system PIN/password is no longer used as an app fallback. If biometric unavailable/dismissed, `onFailure()` is called so `LockScreenActivity` shows the in-app PIN field. Added `isAvailable(Context)` helper.
- **`LockScreenActivity.java`** — added 5-attempt fail counter: wrong PIN 5× triggers `WipeHelper.wipeAll()`; remaining-attempts message displayed after each failure. Biometric button only shown if `biometric_enabled` AND biometric is actually enrolled. If no app PIN is set, the lock screen skips itself immediately.
- **`activity_settings.xml`** — added **App PIN** section (above biometric section) with `etNewPin`, `etConfirmPin`, `btnSetPin`, `btnClearPin`, and `tvPinStatus`. Rewrote layout to use `@style/SectionHeader` and `@style/Divider` from themes.xml.
- **`SettingsActivity.java`** — wired app PIN setup: validates 4–6 digits, checks PINs match, prevents app PIN == duress PIN, calls `PinManager.setPin()`. Clear PIN: dialog-confirmed, also disables biometric switch. Duress PIN: validates 4–6 digits, prevents duress == app PIN. Biometric switch: refuses to enable if no app PIN set or no biometric enrolled. Fixed duress description ("at the lock screen instead of your app PIN").
- **`themes.xml`** — added `@style/SectionHeader` and `@style/Divider` reusable styles for settings layout.

### Previous session
- All 85 Java files across all packages
- All 14 layouts (IDs reconciled with activities)
- 38 drawables including `ic_launcher_foreground.xml`
- 7 anim files: `slide_in_right`, `slide_out_left`, `slide_in_left`, `slide_out_right`, `fade_in`, `fade_out`, `scale_in`
- Adaptive launcher icon: `mipmap-anydpi-v26/ic_launcher.xml` + `ic_launcher_round.xml`
- Manifest updated: `@mipmap/ic_launcher` + `android:roundIcon`
- `themes.xml`: added `WindowAnimation.DuoShield` for global slide transitions
- `proguard-rules.pro`: added PhotoView + security-crypto rules
- `BaseActivity`: `navigateTo()` / `navigateBack()` with transition overrides
- `ChatMediaActivity`: extends `BaseActivity`; `msgListener`/`convListener` detached in `onStop()`
- `PairingActivity` + `SettingsActivity`: extend `BaseActivity` (FLAG_SECURE inherited)
- `colors_bubbles.xml`: emptied (deduplication)

---

## Key SharedPreferences Keys (`duoshield_prefs`)
| Key | Type | Purpose |
|-----|------|---------|
| `my_uid` | String | Firebase Auth UID |
| `conversation_id` | String | Active conversation Firestore doc ID |
| `is_paired` | boolean | Whether ECDH pairing is complete |
| `partner_uid` | String | Partner's Firebase UID |
| `biometric_enabled` | boolean | Biometric lock on/off |
| `app_pin_hash` | String | SHA-256 of PIN (in PinManager) |
| `app_lock_bg_ts` | long | Timestamp when app was backgrounded |
| `fcm_token` | String | Current FCM registration token |

---

## Firebase Firestore Schema
```
/conversations/{convId}
  ├── partnerA: uid
  ├── partnerB: uid
  ├── lastMessage: string
  ├── lastMessageTs: Timestamp
  ├── unread_{uid}: number
  ├── typing_{uid}: boolean
  ├── online_{uid}: boolean
  ├── lastSeen_{uid}: Timestamp
  ├── muted_{uid}: boolean
  ├── pinnedMessages: [{id, preview}]
  └── /messages/{msgId}
        ├── sender: uid
        ├── text: string (encrypted)
        ├── type: "text"|"image"|"video"|"voice"|"contact_card"
        ├── mediaUrl: string
        ├── timestamp: Timestamp
        ├── status: "sent"|"delivered"|"read"
        ├── encrypted: boolean
        ├── edited: boolean
        ├── expiresAt: number (epoch ms, 0 = no expiry)
        ├── replyToId: string
        ├── replyToText: string
        ├── reaction: string (emoji)
        └── reactions: {emoji: [uid]}

/users/{uid}
  ├── token: string (FCM)
  ├── platform: "android"
  └── updatedAt: Timestamp
```

---

## How To Build the APK

```bash
# 1. Place google-services.json in app/
# 2. Open in Android Studio (or use command line)
./gradlew assembleDebug
# APK at: app/build/outputs/apk/debug/app-debug.apk

# For release:
./gradlew assembleRelease
# (requires signing config in build.gradle)
```

---

## To Continue Building on Another Account
1. Fork or clone this repo from GitHub (after token is updated and push completes)
2. Paste this entire HANDOFF.md as the first message context
3. Tell the agent: **"Continue building DuoShield from the handoff. Pick up from 'What Still Needs To Be Done' section."**
4. The agent will have everything it needs to continue without duplicating work.
