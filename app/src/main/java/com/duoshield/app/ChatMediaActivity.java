package com.duoshield.app;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.duoshield.app.crypto.CryptoHelper;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.crypto.ECDHHelper;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
// BiometricHelper removed from direct use here — lock handled by BaseActivity
import com.duoshield.app.ui.MessageAdapter;
import com.duoshield.app.ui.SettingsActivity;
import com.duoshield.app.ui.WaveformView;
import com.duoshield.app.util.ConversationMetaUpdater;
import com.duoshield.app.util.DeliveryReceiptHelper;
import com.duoshield.app.util.SecurePrefs;
import com.duoshield.app.util.VoiceMessagePlayer;
import com.duoshield.app.util.VoiceRecorderHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.SetOptions;
import com.google.firebase.firestore.WriteBatch;
import java.security.PrivateKey;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.duoshield.app.util.SupabaseStorageHelper;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.SecretKey;

public class ChatMediaActivity extends BaseActivity {

    private static final String TAG                  = "ChatMediaActivity";
    private static final int    MAX_PINS             = 3;
    private static final int    REQUEST_RECORD_AUDIO = 201;

    // Typing debounce
    private final Handler typingHandler = new Handler(Looper.getMainLooper());
    private boolean isTyping = false;

    // Reply state
    private String pendingReplyId      = null;
    private String pendingReplyPreview = null;

    // Pinned messages
    private List<Map<String, Object>> pinnedList    = new ArrayList<>();
    private int                       pinnedViewIdx = 0;

    private FirebaseFirestore db;

    // Header views
    private ImageView    ivPartnerAvatar;
    private TextView     tvAvatarInitial, tvPartnerName, tvOnlineStatus;
    private View         headerOnlineDot;

    // Chat views
    private EditText     messageInput;
    private ImageView    sendButton, uploadButton, micButton;
    private ProgressBar  uploadProgress;
    private RecyclerView recyclerView;
    private LinearLayout typingIndicatorRow;
    private TextView     typingIndicator;
    private View         replyPreviewBar;
    private TextView     replyPreviewBarText;
    private ImageView    cancelReplyBtn;

    // Pinned banner
    private LinearLayout pinnedBanner;
    private TextView     pinnedText, pinnedCount;
    private ImageView    pinnedCloseBtn;

    // Voice recording
    private View         voiceRecordingBar;
    private WaveformView recordingWaveform;
    private TextView     recordingTimer;
    private ImageView    cancelRecordingBtn, stopRecordingBtn;

    private final VoiceRecorderHelper recorder = new VoiceRecorderHelper();
    private final VoiceMessagePlayer  player   = new VoiceMessagePlayer();
    private final Handler recordingTimerHandler = new Handler(Looper.getMainLooper());
    private int    recordingSeconds    = 0;
    private String currentlyPlayingId = null;

    private MessageAdapter adapter;
    private String conversationId;
    private String myUid;
    private String partnerUid;

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private ListenerRegistration  msgListener;
    private ListenerRegistration  convListener;

    private final ActivityResultLauncher<String> pickImageLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadMedia(uri, "image"); });

    private final ActivityResultLauncher<String> pickVideoLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadMedia(uri, "video"); });

    private final ActivityResultLauncher<String> pickWallpaperLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) {
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit()
                    .putString("wallpaper_type", "image")
                    .putString("wallpaper_uri", uri.toString()).apply();
                applyWallpaper();
            }
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Lock-screen redirect is handled by BaseActivity.onResume() →
        // AppLockManager.shouldLock(). Calling BiometricHelper here caused
        // a silent finish() when no biometrics were enrolled.
        setupChat();
    }

    private void setupChat() {
        setContentView(R.layout.activity_chat_media);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        conversationId = prefs.getString("conversation_id", null);
        myUid          = prefs.getString("my_uid", null);
        if (myUid == null) {
            com.google.firebase.auth.FirebaseUser fu = FirebaseAuth.getInstance().getCurrentUser();
            if (fu != null) myUid = fu.getUid();
        }
        partnerUid     = prefs.getString("partner_uid", null);

        if (conversationId == null) {
            Toast.makeText(this, "No active conversation. Please pair first.", Toast.LENGTH_LONG).show();
            finish(); return;
        }
        if (myUid == null) {
            Toast.makeText(this, "Authentication error. Please sign in again.", Toast.LENGTH_LONG).show();
            finish(); return;
        }

        // ── Header ──────────────────────────────────────────────────
        ivPartnerAvatar  = findViewById(R.id.ivPartnerAvatar);
        tvAvatarInitial  = findViewById(R.id.tvAvatarInitial);
        tvPartnerName    = findViewById(R.id.tvPartnerName);
        tvOnlineStatus   = findViewById(R.id.tvOnlineStatus);
        headerOnlineDot  = findViewById(R.id.headerOnlineDot);

        ImageView btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> onBackPressed());

        ImageView btnFingerprint = findViewById(R.id.btnFingerprint);
        if (btnFingerprint != null) btnFingerprint.setOnClickListener(v ->
            startActivity(new Intent(this, KeyFingerprintActivity.class)));

        ImageView btnOverflow = findViewById(R.id.btnOverflow);
        if (btnOverflow != null) btnOverflow.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, v);
            popup.getMenu().add(0, 1, 0, "Settings");
            popup.getMenu().add(0, 2, 0, "Set Wallpaper");
            popup.getMenu().add(0, 3, 0, "Search Messages");
            popup.setOnMenuItemClickListener(item -> {
                int id = item.getItemId();
                if (id == 1) { startActivity(new Intent(this, SettingsActivity.class)); return true; }
                if (id == 2) { showWallpaperDialog(); return true; }
                if (id == 3) { startActivity(new Intent(this, MessageSearchActivity.class)); return true; }
                return false;
            });
            popup.show();
        });

        // ── Chat views ──────────────────────────────────────────────
        messageInput        = findViewById(R.id.messageInput);
        sendButton          = findViewById(R.id.sendButton);
        uploadButton        = findViewById(R.id.uploadButton);
        micButton           = findViewById(R.id.micButton);
        uploadProgress      = findViewById(R.id.uploadProgress);
        recyclerView        = findViewById(R.id.messageRecycler);
        typingIndicatorRow  = findViewById(R.id.typingIndicatorRow);
        typingIndicator     = findViewById(R.id.typingIndicator);
        replyPreviewBar     = findViewById(R.id.replyPreviewBar);
        replyPreviewBarText = findViewById(R.id.replyPreviewBarText);
        cancelReplyBtn      = findViewById(R.id.cancelReplyBtn);
        pinnedBanner        = findViewById(R.id.pinnedBanner);
        pinnedText          = findViewById(R.id.pinnedText);
        pinnedCount         = findViewById(R.id.pinnedCount);
        pinnedCloseBtn      = findViewById(R.id.pinnedCloseBtn);

        // Voice recording
        voiceRecordingBar  = findViewById(R.id.voiceRecordingBar);
        recordingWaveform  = findViewById(R.id.recordingWaveform);
        recordingTimer     = findViewById(R.id.recordingTimer);
        cancelRecordingBtn = findViewById(R.id.cancelRecordingBtn);
        stopRecordingBtn   = findViewById(R.id.stopRecordingBtn);

        // ── Critical-view null guard ─────────────────────────────────────────
        // If the layout is missing any of these we cannot function — bail safely.
        if (recyclerView == null || messageInput == null
                || sendButton == null || micButton == null) {
            Toast.makeText(this,
                    "Chat layout failed to load. Please reinstall the app.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        adapter = new MessageAdapter(new ArrayList<>(), myUid, this::onVoicePlay,
            (msg, anchor) -> showMessageActionDialog(msg));
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        recyclerView.setLayoutManager(llm);
        recyclerView.setAdapter(adapter);

        db         = FirebaseFirestore.getInstance();

        applyWallpaper();
        loadPartnerInfo();
        listenForConvUpdates();
        // reEnsureEcdhKey starts the message listener AFTER the key is ready so
        // historical messages are decrypted with the correct key on first load.
        reEnsureEcdhKey();

        sendButton.setOnClickListener(v -> {
            String text = messageInput.getText() != null
                    ? messageInput.getText().toString().trim() : "";
            if (!text.isEmpty()) { sendMessage(text); messageInput.setText(""); }
        });
        if (uploadButton    != null) uploadButton.setOnClickListener(v -> showMediaTypePopup());
        micButton.setOnClickListener(v -> startVoiceRecording());
        if (cancelReplyBtn  != null) cancelReplyBtn.setOnClickListener(v -> clearReplyMode());
        if (cancelRecordingBtn != null) cancelRecordingBtn.setOnClickListener(v -> cancelVoiceRecording());
        if (stopRecordingBtn   != null) stopRecordingBtn.setOnClickListener(v -> stopAndSendVoiceRecording());

        messageInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {
                onUserTyping();
                boolean hasText = s.length() > 0;
                sendButton.setVisibility(hasText ? View.VISIBLE : View.GONE);
                micButton.setVisibility(hasText  ? View.GONE   : View.VISIBLE);
            }
        });

        if (pinnedBanner   != null) pinnedBanner.setOnClickListener(v -> cycleAndScrollToPin());
        if (pinnedCloseBtn != null && pinnedBanner != null)
            pinnedCloseBtn.setOnClickListener(v -> pinnedBanner.setVisibility(View.GONE));
    }

    // ══════════════════════════════════════════════════════════════
    // PARTNER INFO IN HEADER
    // ══════════════════════════════════════════════════════════════

    private void loadPartnerInfo() {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String storedName = prefs.getString("partner_name", null);
        String storedPhoto = prefs.getString("partner_photo_url", null);

        if (storedName != null && !storedName.isEmpty()) {
            tvPartnerName.setText(storedName);
            setAvatarInitial(storedName);
        }
        if (storedPhoto != null && !storedPhoto.isEmpty()) {
            tvAvatarInitial.setVisibility(View.GONE);
            ivPartnerAvatar.setVisibility(View.VISIBLE);
            Glide.with(this).load(storedPhoto).circleCrop().into(ivPartnerAvatar);
        }

        if (partnerUid != null) {
            db.collection("users").document(partnerUid).get()
              .addOnSuccessListener(doc -> {
                  if (!doc.exists()) return;
                  Object name  = doc.get("displayName");
                  Object photo = doc.get("photoUrl");
                  if (name != null)  {
                      tvPartnerName.setText(name.toString());
                      setAvatarInitial(name.toString());
                  }
                  if (photo != null && !photo.toString().isEmpty()) {
                      tvAvatarInitial.setVisibility(View.GONE);
                      ivPartnerAvatar.setVisibility(View.VISIBLE);
                      Glide.with(this).load(photo.toString()).circleCrop().into(ivPartnerAvatar);
                  }
              });
        }
    }

    private void setAvatarInitial(String name) {
        String initial = name.isEmpty() ? "?" : String.valueOf(name.charAt(0)).toUpperCase();
        tvAvatarInitial.setText(initial);
        tvAvatarInitial.setVisibility(View.VISIBLE);
        ivPartnerAvatar.setVisibility(View.INVISIBLE);
    }

    private void updateOnlineStatus(boolean online, long lastSeenMs) {
        headerOnlineDot.setVisibility(online ? View.VISIBLE : View.GONE);
        if (online) {
            tvOnlineStatus.setText("online");
            tvOnlineStatus.setTextColor(0xFF4CAF50);
        } else if (lastSeenMs > 0) {
            tvOnlineStatus.setText("last seen " + formatLastSeen(lastSeenMs));
            tvOnlineStatus.setTextColor(0xFF888888);
        } else {
            tvOnlineStatus.setText("🔒 end-to-end encrypted");
            tvOnlineStatus.setTextColor(0xFF888888);
        }
    }

    private String formatLastSeen(long epochMs) {
        long diff = System.currentTimeMillis() - epochMs;
        if (diff < 60_000) return "just now";
        if (diff < 3600_000) return (diff / 60_000) + "m ago";
        if (diff < 86400_000) return (diff / 3600_000) + "h ago";
        return new java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())
            .format(new java.util.Date(epochMs));
    }

    // ══════════════════════════════════════════════════════════════
    // VOICE RECORDING
    // ══════════════════════════════════════════════════════════════

    private void startVoiceRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO);
            return;
        }
        recordingSeconds = 0;
        recordingTimer.setText("0:00");
        recordingWaveform.clear();
        voiceRecordingBar.setVisibility(View.VISIBLE);
        View inputBar = findViewById(R.id.inputBar);
        if (inputBar != null) inputBar.setVisibility(View.GONE);
        recordingTimerHandler.post(timerTick);

        recorder.start(this, new VoiceRecorderHelper.RecorderListener() {
            @Override public void onAmplitude(int amp)  { recordingWaveform.addAmplitude(amp); }
            @Override public void onStopped(String filePath, List<Integer> amplitudes) {
                uploadVoiceNote(filePath, amplitudes);
            }
            @Override public void onError(String msg) {
                runOnUiThread(() -> {
                    Toast.makeText(ChatMediaActivity.this, "Recording error: " + msg, Toast.LENGTH_SHORT).show();
                    dismissRecordingUI();
                });
            }
        });
    }

    private final Runnable timerTick = new Runnable() {
        @Override public void run() {
            recordingSeconds++;
            recordingTimer.setText(String.format(Locale.US, "%d:%02d",
                recordingSeconds / 60, recordingSeconds % 60));
            recordingTimerHandler.postDelayed(this, 1000);
        }
    };

    private void cancelVoiceRecording() {
        recordingTimerHandler.removeCallbacks(timerTick);
        recorder.cancel();
        dismissRecordingUI();
    }

    private void stopAndSendVoiceRecording() {
        recordingTimerHandler.removeCallbacks(timerTick);
        recorder.stop();
        dismissRecordingUI();
    }

    private void dismissRecordingUI() {
        voiceRecordingBar.setVisibility(View.GONE);
        View inputBar = findViewById(R.id.inputBar);
        if (inputBar != null) inputBar.setVisibility(View.VISIBLE);
    }

    private void uploadVoiceNote(String filePath, List<Integer> amplitudes) {
        File f = new File(filePath);
        if (!f.exists()) return;
        runOnUiThread(() -> { uploadProgress.setVisibility(View.VISIBLE); uploadProgress.setProgress(0); });
        String path = "voice/" + conversationId + "/" + UUID.randomUUID() + ".3gp";
        executor.execute(() -> {
            try {
                byte[] data = readFileBytes(f);
                data = SupabaseStorageHelper.encryptBeforeUpload(
                        data, CryptoInitializer.getSharedKey(ChatMediaActivity.this));
                String storagePath = SupabaseStorageHelper.uploadFile(
                        data, path, "audio/3gpp",
                        pct -> runOnUiThread(() -> uploadProgress.setProgress(pct)));
                runOnUiThread(() -> uploadProgress.setVisibility(View.GONE));
                sendVoiceMessage(storagePath);
                f.delete();
            } catch (Exception e) {
                Log.e(TAG, "Voice upload failed", e);
                runOnUiThread(() -> {
                    uploadProgress.setVisibility(View.GONE);
                    Toast.makeText(ChatMediaActivity.this, "Voice upload failed", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private static byte[] readFileBytes(File f) throws java.io.IOException {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(f);
             java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        }
    }

    private byte[] readUriBytes(Uri uri) throws java.io.IOException {
        java.io.InputStream is = getContentResolver().openInputStream(uri);
        if (is == null) throw new java.io.IOException("Cannot open URI: " + uri);
        try (java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return baos.toByteArray();
        } finally { is.close(); }
    }

    private void sendVoiceMessage(String storagePath) {
        String msgId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", "");
        doc.put("path", storagePath);           // private path — NOT a public URL
        doc.put("mediaType", "voice");
        doc.put("type", "voice");
        // Bug 14: reflect whether the file bytes were encrypted before upload
        doc.put("isEncrypted", CryptoInitializer.getSharedKey(this) != null);
        doc.put("expiresAt", exp); doc.put("timestamp", FieldValue.serverTimestamp());
        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              Message m = new Message(msgId, conversationId, myUid, "", now, false, storagePath, "voice");
              m.setExpiresAt(exp);
              saveToRoom(m);
              notifyPartner("DuoShield", "Sent a voice note");
          });
    }

    // ══════════════════════════════════════════════════════════════
    // VOICE PLAYBACK
    // ══════════════════════════════════════════════════════════════

    private void onVoicePlay(Message msg, ImageView playPauseBtn,
                             WaveformView waveform, TextView durationView) {
        if (msg.getId().equals(currentlyPlayingId)) {
            player.pause();
            currentlyPlayingId = null;
            adapter.setPlayingMessageId(null);
            playPauseBtn.setImageResource(R.drawable.ic_play_video);
            return;
        }
        player.release();
        currentlyPlayingId = msg.getId();
        adapter.setPlayingMessageId(msg.getId());
        playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);

        VoiceMessagePlayer.PlayerListener listener = new VoiceMessagePlayer.PlayerListener() {
            @Override public void onStart(int durationMs) {
                runOnUiThread(() -> durationView.setText(MessageAdapter.formatDuration(durationMs)));
            }
            @Override public void onProgress(int posMs) {
                runOnUiThread(() -> durationView.setText(MessageAdapter.formatDuration(posMs)));
            }
            @Override public void onComplete() {
                runOnUiThread(() -> {
                    currentlyPlayingId = null;
                    adapter.setPlayingMessageId(null);
                    playPauseBtn.setImageResource(R.drawable.ic_play_video);
                    waveform.setProgress(0f);
                });
            }
            @Override public void onError(String err) {
                runOnUiThread(() -> {
                    Toast.makeText(ChatMediaActivity.this, "Playback error", Toast.LENGTH_SHORT).show();
                    currentlyPlayingId = null;
                    adapter.setPlayingMessageId(null);
                    playPauseBtn.setImageResource(R.drawable.ic_play_video);
                });
            }
        };

        String voiceRef = msg.getMediaUrl();
        if (SupabaseStorageHelper.isSupabasePath(voiceRef)) {
            // Decrypt then write to temp file — MediaPlayer reads local file paths directly
            javax.crypto.SecretKey vKey = CryptoInitializer.getSharedKey(ChatMediaActivity.this);
            SupabaseStorageHelper.loadMedia(voiceRef, vKey, new SupabaseStorageHelper.MediaCallback() {
                @Override public void onLoaded(byte[] plainBytes) {
                    if (!msg.getId().equals(currentlyPlayingId)) return; // user tapped stop
                    try {
                        File tmp = File.createTempFile("voice_", ".3gp", getCacheDir());
                        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tmp)) {
                            fos.write(plainBytes);
                        }
                        tmp.deleteOnExit();
                        player.play(tmp.getAbsolutePath(), listener);
                    } catch (Exception ex) {
                        runOnUiThread(() -> {
                            Toast.makeText(ChatMediaActivity.this,
                                    "Playback error", Toast.LENGTH_SHORT).show();
                            currentlyPlayingId = null;
                            adapter.setPlayingMessageId(null);
                            playPauseBtn.setImageResource(R.drawable.ic_play_video);
                        });
                    }
                }
                @Override public void onError(Exception e) {
                    runOnUiThread(() -> {
                        Toast.makeText(ChatMediaActivity.this,
                                "Couldn't load voice note", Toast.LENGTH_SHORT).show();
                        currentlyPlayingId = null;
                        adapter.setPlayingMessageId(null);
                        playPauseBtn.setImageResource(R.drawable.ic_play_video);
                    });
                }
            });
        } else {
            // Legacy Firebase Storage URL — play directly
            player.play(voiceRef, listener);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecording();
            } else {
                Toast.makeText(this,
                        "Microphone permission is required to record voice messages.",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        markMessagesAsReadAndSeen();
        clearBadge();
        applyWallpaper();
        // Restart Firestore listeners that were detached in onStop (e.g. after opening Settings)
        if (conversationId != null) {
            if (msgListener  == null) reEnsureEcdhKey();   // re-derives key then starts listener
            if (convListener == null) listenForConvUpdates();
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (msgListener  != null) { msgListener.remove();  msgListener  = null; }
        if (convListener != null) { convListener.remove(); convListener = null; }
        typingHandler.removeCallbacksAndMessages(null);
        recordingTimerHandler.removeCallbacks(timerTick);
        player.release();
        if (isTyping && conversationId != null && myUid != null) {
            db.collection("chats").document(conversationId)
              .update("typing_" + myUid, false);
            isTyping = false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FIRESTORE LISTENERS
    // ══════════════════════════════════════════════════════════════

    private void listenForConvUpdates() {
        convListener = db.collection("chats").document(conversationId)
          .addSnapshotListener((snap, e) -> {
              if (snap == null) return;

              // Pinned messages
              Object pinnedRaw = snap.get("pinnedMessages");
              List<Map<String, Object>> raw = null;
              if (pinnedRaw instanceof List) {
                  try {
                      //noinspection unchecked
                      raw = (List<Map<String, Object>>) pinnedRaw;
                  } catch (ClassCastException ignored) {}
              }
              pinnedList = raw != null ? raw : new ArrayList<>();
              Set<String> ids = new HashSet<>();
              for (Map<String, Object> m : pinnedList) {
                  Object id = m.get("id");
                  if (id instanceof String) ids.add((String) id);
              }
              adapter.updatePinnedIds(ids);
              refreshPinnedBanner();

              // Typing
              Object typing = snap.get("typing_" + partnerUid);
              typingIndicatorRow.setVisibility(
                  Boolean.TRUE.equals(typing) ? View.VISIBLE : View.GONE);

              // Online / last seen
              Object online   = snap.get("online_"   + partnerUid);
              Object lastSeen = snap.get("lastSeen_" + partnerUid);
              long lastSeenMs = 0;
              if (lastSeen instanceof com.google.firebase.Timestamp)
                  lastSeenMs = ((com.google.firebase.Timestamp) lastSeen).toDate().getTime();
              updateOnlineStatus(Boolean.TRUE.equals(online), lastSeenMs);
          });
    }

    private void listenForMessages() {
        if (msgListener != null) return; // already active — do not create a duplicate
        msgListener = db.collection("chats").document(conversationId)
          .collection("messages").orderBy("timestamp")
          .addSnapshotListener((snaps, e) -> {
              if (snaps == null) return;
              boolean newMessageAdded = false;
              List<Message> newIncoming = new ArrayList<>();

              for (DocumentChange dc : snaps.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String id    = dc.getDocument().getString("id");
                      String convo = dc.getDocument().getString("conversationId");
                      String from  = dc.getDocument().getString("sender");
                      String text  = dc.getDocument().getString("text");
                      // "path" = Supabase private path (new); "mediaUrl" = legacy Firebase URL
                      String mUrl  = dc.getDocument().getString("path");
                      if (mUrl == null) mUrl = dc.getDocument().getString("mediaUrl");
                      String mType = dc.getDocument().getString("mediaType");
                      String rpId  = dc.getDocument().getString("replyToId");
                      String rpPrv = dc.getDocument().getString("replyPreview");
                      Long   expAt = dc.getDocument().getLong("expiresAt");
                      long   ts    = System.currentTimeMillis();

                      // Use server timestamp if available
                      com.google.firebase.Timestamp serverTs =
                          dc.getDocument().getTimestamp("timestamp");
                      if (serverTs != null) ts = serverTs.toDate().getTime();

                      if (id != null) {
                          // If already in local list (optimistic entry), just update its status
                          boolean exists = false;
                          for (Message m : adapter.getMessages()) {
                              if (id.equals(m.getId())) {
                                  exists = true;
                                  // Update status from Firestore (e.g. "pending" → "sent")
                                  String fsStatus = dc.getDocument().getString("status");
                                  if (fsStatus != null) {
                                      adapter.updateMessage(id, msg -> msg.setStatus(fsStatus));
                                  } else if ("pending".equals(m.getStatus())) {
                                      adapter.updateMessage(id, msg -> msg.setStatus("sent"));
                                  }
                                  break;
                              }
                          }
                          if (exists) continue;

                          Boolean isEncFlag = dc.getDocument().getBoolean("isEncrypted");
                          boolean wasEncrypted = Boolean.TRUE.equals(isEncFlag);

                          String displayText = text;
                          if (wasEncrypted && text != null && !text.isEmpty()) {
                              try {
                                  SecretKey k = CryptoInitializer.getSharedKey(ChatMediaActivity.this);
                                  if (k != null) {
                                      displayText = CryptoHelper.decrypt(text, k);
                                  } else {
                                      // ECDH key not ready yet — reEnsureEcdhKey() will restart
                                      // the listener with the correct key once derivation completes.
                                      displayText = "[Waiting for encryption key…]";
                                      Log.w(TAG, "ECDH key missing for msg " + id + " — will retry after key derivation");
                                  }
                              } catch (Exception ex) {
                                  displayText = "[Decryption failed]";
                                  Log.w(TAG, "Decrypt failed for msg " + id, ex);
                              }
                          }

                          String statusFromFs = dc.getDocument().getString("status");
                          Message m = new Message(id, convo, from, displayText, ts, wasEncrypted, mUrl, mType);
                          if (rpId      != null) m.setReplyToId(rpId);
                          if (rpPrv     != null) m.setReplyPreview(rpPrv);
                          if (expAt     != null) m.setExpiresAt(expAt);
                          if (statusFromFs != null) m.setStatus(statusFromFs);
                          if (isExpired(m)) continue;

                          adapter.appendMessage(m);
                          saveToRoom(m);
                          newMessageAdded = true;

                          // Track incoming partner messages for delivery receipt
                          if (!myUid.equals(from)) newIncoming.add(m);
                      }

                  } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                      // Handle status updates (ticks) AND reaction updates
                      String id       = dc.getDocument().getString("id");
                      String reaction = dc.getDocument().getString("reaction");
                      String status   = dc.getDocument().getString("status");
                      if (id != null) {
                          final String finalReaction = reaction;
                          final String finalStatus   = status;
                          adapter.updateMessage(id, msg -> {
                              if (finalReaction != null) msg.setReaction(finalReaction);
                              if (finalStatus   != null) msg.setStatus(finalStatus);
                          });
                      }
                  }
              }

              if (newMessageAdded) {
                  int last = adapter.getItemCount() - 1;
                  if (last >= 0) recyclerView.scrollToPosition(last);
              }

              // Mark newly received partner messages as "delivered"
              if (!newIncoming.isEmpty()) {
                  DeliveryReceiptHelper.markDelivered(conversationId, newIncoming, myUid);
              }
          });
    }

    // ══════════════════════════════════════════════════════════════
    // PINNING
    // ══════════════════════════════════════════════════════════════

    private void refreshPinnedBanner() {
        if (pinnedBanner == null) return;
        if (pinnedList.isEmpty()) { pinnedBanner.setVisibility(View.GONE); return; }
        pinnedBanner.setVisibility(View.VISIBLE);
        if (pinnedViewIdx >= pinnedList.size()) pinnedViewIdx = 0;
        Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
        Object preview = pin.get("preview");
        if (pinnedText  != null) pinnedText.setText(preview != null ? preview.toString() : "Pinned message");
        if (pinnedCount != null) pinnedCount.setText(pinnedList.size() > 1 ? (pinnedViewIdx + 1) + "/" + pinnedList.size() : "");
    }

    private void cycleAndScrollToPin() {
        if (pinnedList.isEmpty()) return;
        pinnedViewIdx = (pinnedViewIdx + 1) % pinnedList.size();
        refreshPinnedBanner();
        Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
        Object targetId = pin.get("id");
        if (targetId == null) return;
        List<Message> msgs = adapter.getMessages();
        for (int i = 0; i < msgs.size(); i++) {
            if (targetId.toString().equals(msgs.get(i).getId())) {
                recyclerView.smoothScrollToPosition(i); break;
            }
        }
    }

    private void pinMessage(Message msg) {
        for (Map<String, Object> p : pinnedList)
            if (msg.getId().equals(p.get("id"))) { Toast.makeText(this, "Already pinned", Toast.LENGTH_SHORT).show(); return; }
        if (pinnedList.size() >= MAX_PINS) { Toast.makeText(this, "Max " + MAX_PINS + " pins", Toast.LENGTH_SHORT).show(); return; }
        String preview = (msg.getText() != null && !msg.getText().isEmpty()) ? msg.getText() : "[media]";
        Map<String, Object> entry = new HashMap<>(); entry.put("id", msg.getId()); entry.put("preview", preview);
        db.collection("chats").document(conversationId)
          .update("pinnedMessages", FieldValue.arrayUnion(entry))
          .addOnSuccessListener(v -> Toast.makeText(this, "Pinned 📌", Toast.LENGTH_SHORT).show())
          .addOnFailureListener(ex -> {
              Map<String, Object> d = new HashMap<>(); d.put("pinnedMessages", Arrays.asList(entry));
              db.collection("chats").document(conversationId)
                .set(d, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(v2 -> Toast.makeText(this, "Pinned 📌", Toast.LENGTH_SHORT).show());
          });
    }

    private void unpinMessage(Message msg) {
        Map<String, Object> toRemove = null;
        for (Map<String, Object> p : pinnedList) if (msg.getId().equals(p.get("id"))) { toRemove = p; break; }
        if (toRemove == null) { Toast.makeText(this, "Not pinned", Toast.LENGTH_SHORT).show(); return; }
        db.collection("chats").document(conversationId)
          .update("pinnedMessages", FieldValue.arrayRemove(toRemove))
          .addOnSuccessListener(v -> Toast.makeText(this, "Unpinned", Toast.LENGTH_SHORT).show());
    }

    private boolean isPinned(Message msg) {
        for (Map<String, Object> p : pinnedList) if (msg.getId().equals(p.get("id"))) return true;
        return false;
    }

    // ══════════════════════════════════════════════════════════════
    // MESSAGE ACTION DIALOG
    // ══════════════════════════════════════════════════════════════

    private void showMessageActionDialog(Message msg) {
        boolean pinned = isPinned(msg);
        boolean mine   = myUid != null && myUid.equals(msg.getSender());
        String[] options = {
            pinned ? "Unpin" : "Pin",
            "Reply",
            "React",
            mine ? "Delete for me" : "Delete locally"
        };
        new AlertDialog.Builder(this)
            .setItems(options, (d, which) -> {
                switch (which) {
                    case 0: if (pinned) unpinMessage(msg); else pinMessage(msg); break;
                    case 1: enterReplyMode(msg); break;
                    case 2: showReactionPicker(msg); break;
                    case 3: adapter.removeMessage(msg.getId()); break;
                }
            }).show();
    }

    private void showReactionPicker(Message msg) {
        String[] emojis = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
        new AlertDialog.Builder(this).setTitle("React")
            .setItems(emojis, (d, w) -> {
                db.collection("chats").document(conversationId)
                  .collection("messages").document(msg.getId()).update("reaction", emojis[w]);
                adapter.updateMessage(msg.getId(), m -> m.setReaction(emojis[w]));
            }).show();
    }

    // ══════════════════════════════════════════════════════════════
    // TYPING
    // ══════════════════════════════════════════════════════════════

    private void onUserTyping() {
        if (!isTyping) {
            isTyping = true;
            db.collection("chats").document(conversationId).update("typing_" + myUid, true);
        }
        typingHandler.removeCallbacksAndMessages(null);
        typingHandler.postDelayed(() -> {
            isTyping = false;
            db.collection("chats").document(conversationId).update("typing_" + myUid, false);
        }, 3000);
    }

    // ══════════════════════════════════════════════════════════════
    // REPLY
    // ══════════════════════════════════════════════════════════════

    private void enterReplyMode(Message msg) {
        pendingReplyId      = msg.getId();
        pendingReplyPreview = (msg.getText() != null && !msg.getText().isEmpty()) ? msg.getText() : "[media]";
        replyPreviewBarText.setText("↩  " + pendingReplyPreview);
        replyPreviewBar.setVisibility(View.VISIBLE);
        messageInput.requestFocus();
    }

    private void clearReplyMode() {
        pendingReplyId = null; pendingReplyPreview = null;
        replyPreviewBar.setVisibility(View.GONE);
    }

    // ══════════════════════════════════════════════════════════════
    // DISAPPEARING / EXPIRED
    // ══════════════════════════════════════════════════════════════

    private long getDisappearMs() {
        return getSharedPreferences("duoshield_prefs", MODE_PRIVATE).getLong("disappear_ms", 0);
    }

    private boolean isExpired(Message m) {
        return m.getExpiresAt() > 0 && System.currentTimeMillis() > m.getExpiresAt();
    }

    // ══════════════════════════════════════════════════════════════
    // WALLPAPER
    // ══════════════════════════════════════════════════════════════

    private void applyWallpaper() {
        if (recyclerView == null) return;
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        switch (prefs.getString("wallpaper_type", "none")) {
            case "color":
                recyclerView.setBackgroundColor(prefs.getInt("wallpaper_color", Color.TRANSPARENT)); break;
            case "image":
                String u = prefs.getString("wallpaper_uri", null);
                if (u != null) Glide.with(this).load(Uri.parse(u)).centerCrop()
                    .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                        @Override public void onResourceReady(android.graphics.drawable.Drawable r, com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> t) { recyclerView.setBackground(r); }
                        @Override public void onLoadCleared(android.graphics.drawable.Drawable p) { recyclerView.setBackground(null); }
                    });
                break;
            default: recyclerView.setBackground(null);
        }
    }

    private void showWallpaperDialog() {
        String[] opts   = {"None", "Soft Blue", "Forest Green", "Dark Night", "Blush Pink", "Pick from gallery…"};
        int[]    colors = {0, 0xFFDCEEFB, 0xFFD7EDDC, 0xFF1A1A2E, 0xFFFDE8EC};
        new AlertDialog.Builder(this).setTitle("Chat wallpaper")
            .setItems(opts, (d, w) -> {
                SharedPreferences.Editor ed = getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit();
                if (w == opts.length - 1) pickWallpaperLauncher.launch("image/*");
                else if (w == 0) { ed.putString("wallpaper_type", "none").apply(); applyWallpaper(); }
                else { ed.putString("wallpaper_type", "color").putInt("wallpaper_color", colors[w]).apply(); applyWallpaper(); }
            }).show();
    }

    // ══════════════════════════════════════════════════════════════
    // BADGE
    // ══════════════════════════════════════════════════════════════

    private void clearBadge() {
        NotificationManagerCompat.from(this).cancelAll();
        getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit().putInt("badge_count", 0).apply();
    }

    // ══════════════════════════════════════════════════════════════
    // SEND MESSAGE
    // ══════════════════════════════════════════════════════════════

    private void showMediaTypePopup() {
        new AlertDialog.Builder(this).setTitle("Share")
            .setItems(new String[]{"🖼 Image", "🎬 Video", "👤 Contact Card"}, (d, w) -> {
                if      (w == 0) pickImageLauncher.launch("image/*");
                else if (w == 1) pickVideoLauncher.launch("video/*");
                else             sendContactCard();
            }).show();
    }

    private void uploadMedia(Uri fileUri, String mediaType) {
        runOnUiThread(() -> { uploadProgress.setVisibility(View.VISIBLE); uploadProgress.setProgress(0); });
        String ext  = "video".equals(mediaType) ? ".mp4" : ".jpg";
        String mime = "video".equals(mediaType) ? "video/mp4" : "image/jpeg";
        String path = "media/" + conversationId + "/" + UUID.randomUUID() + ext;
        executor.execute(() -> {
            try {
                byte[] data = readUriBytes(fileUri);
                data = SupabaseStorageHelper.encryptBeforeUpload(
                        data, CryptoInitializer.getSharedKey(ChatMediaActivity.this));
                String storagePath = SupabaseStorageHelper.uploadFile(
                        data, path, mime,
                        pct -> runOnUiThread(() -> uploadProgress.setProgress(pct)));
                runOnUiThread(() -> {
                    uploadProgress.setVisibility(View.GONE);
                    sendMediaMessage(storagePath, mediaType);
                });
            } catch (Exception e) {
                Log.e(TAG, "Media upload failed", e);
                runOnUiThread(() -> {
                    uploadProgress.setVisibility(View.GONE);
                    Toast.makeText(ChatMediaActivity.this, "Upload failed", Toast.LENGTH_SHORT).show();
                });
            }
        });
    }

    private void sendMediaMessage(String storagePath, String mediaType) {
        String msgId = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", "");
        doc.put("path", storagePath);           // private path — NOT a public URL
        doc.put("mediaType", mediaType);
        // Bug 14: reflect whether the file bytes were encrypted before upload
        doc.put("isEncrypted", CryptoInitializer.getSharedKey(this) != null);
        doc.put("type", mediaType);
        doc.put("expiresAt", exp); doc.put("timestamp", FieldValue.serverTimestamp());
        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              Message m = new Message(msgId, conversationId, myUid, "", now, false, storagePath, mediaType);
              m.setExpiresAt(exp); saveToRoom(m);
              notifyPartner("DuoShield", "video".equals(mediaType) ? "Sent a video 🎬" : "Sent a photo 🖼");
          });
    }

    private void sendContactCard() {
        String cardText = "DuoShield User|" + myUid;
        String msgId = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", cardText);
        doc.put("mediaType", "contact_card"); doc.put("type", "contact_card");
        doc.put("isEncrypted", false); doc.put("timestamp", FieldValue.serverTimestamp());
        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              saveToRoom(new Message(msgId, conversationId, myUid, cardText, now, false, null, "contact_card"));
              notifyPartner("DuoShield", "Shared a contact card");
          });
    }

    private void sendMessage(String plaintext) {
        // Only use the ECDH shared key — never fall back to the device-local AES key
        // because the partner cannot decrypt with a key they don't have.
        SecretKey k = CryptoInitializer.getSharedKey(this);
        if (k == null) {
            Toast.makeText(this,
                "Encryption key not ready yet — please wait a moment.", Toast.LENGTH_SHORT).show();
            reEnsureEcdhKey(); // triggers key derivation and starts listener when done
            return;
        }

        String ciphertext;
        try {
            ciphertext = CryptoHelper.encrypt(plaintext, k);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show();
            return;
        }

        String msgId = UUID.randomUUID().toString();
        long   now   = System.currentTimeMillis();
        long   exp   = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        String rId   = pendingReplyId;
        String rPrv  = pendingReplyPreview;
        clearReplyMode();

        // ── Optimistic UI: add message immediately so the sender sees it instantly ──
        Message optimistic = new Message(msgId, conversationId, myUid, plaintext, now, false);
        optimistic.setStatus("pending");
        optimistic.setExpiresAt(exp);
        if (rId != null) { optimistic.setReplyToId(rId); optimistic.setReplyPreview(rPrv); }
        adapter.appendMessage(optimistic);
        int last = adapter.getItemCount() - 1;
        if (last >= 0) recyclerView.scrollToPosition(last);

        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", ciphertext);
        doc.put("isEncrypted", true); doc.put("type", "text");
        doc.put("status", "sent");
        doc.put("expiresAt", exp);
        if (rId != null) { doc.put("replyToId", rId); doc.put("replyPreview", rPrv); }
        doc.put("timestamp", FieldValue.serverTimestamp());

        db.collection("chats").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              // Update optimistic entry: status → "sent", and save plain text to Room
              adapter.updateMessage(msgId, m -> m.setStatus("sent"));
              // Bug 5: store plaintext (not ciphertext) in Room — search & export use Room
              Message stored = new Message(msgId, conversationId, myUid, plaintext, now, false);
              stored.setExpiresAt(exp);
              stored.setStatus("sent");
              if (rId != null) { stored.setReplyToId(rId); stored.setReplyPreview(rPrv); }
              saveToRoom(stored);
              // Bug 7/10: update conversation preview after each sent text message
              ConversationMetaUpdater.update(ChatMediaActivity.this, conversationId, myUid,
                  partnerUid, plaintext.length() > 80 ? plaintext.substring(0, 80) : plaintext);
              notifyPartner("DuoShield", "New message");
          })
          .addOnFailureListener(e -> {
              // Remove the optimistic entry and show error
              adapter.removeMessage(msgId);
              Toast.makeText(this, "Failed to send. Check your connection.", Toast.LENGTH_SHORT).show();
          });
    }

    /**
     * Fetches the partner's EC public key from Firestore, derives (or re-derives)
     * the ECDH shared AES key, then starts listenForMessages().
     *
     * Calling listenForMessages() HERE (not in onCreate) guarantees that historical
     * messages are decrypted with the correct key instead of silently failing.
     * A 4-second watchdog ensures the listener always starts even if Firestore is slow.
     */
    private void reEnsureEcdhKey() {
        // Watchdog: if Firestore takes > 4 s, start listening anyway so the UI isn't stuck
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (msgListener == null) {
                Log.w(TAG, "ECDH watchdog: starting listener without fresh key");
                listenForMessages();
            }
        }, 4_000);

        if (partnerUid == null) {
            listenForMessages();
            return;
        }

        db.collection("users").document(partnerUid).get()
            .addOnSuccessListener(doc -> {
                String partnerPub = doc.getString("ecPublicKey");
                if (partnerPub == null) {
                    Log.w(TAG, "Partner EC public key not in Firestore yet");
                    listenForMessages();
                    return;
                }

                android.content.SharedPreferences secure = SecurePrefs.get(this);
                String storedPartnerPub = secure.getString(
                        CryptoInitializer.KEY_PARTNER_EC_PUBLIC, null);
                SecretKey existingKey = CryptoInitializer.getSharedKey(this);

                // Key already good — no re-derive needed, just start listening
                if (existingKey != null && partnerPub.equals(storedPartnerPub)) {
                    listenForMessages();
                    return;
                }

                executor.execute(() -> {
                    try {
                        PrivateKey myPriv = CryptoInitializer.getMyPrivateKey(this);
                        if (myPriv == null) {
                            CryptoInitializer.ensureKeyExists(this);
                            myPriv = CryptoInitializer.getMyPrivateKey(this);
                            if (myPriv == null) {
                                runOnUiThread(this::listenForMessages);
                                return;
                            }
                            String newPub = CryptoInitializer.getMyPublicKeyB64(this);
                            if (newPub != null && myUid != null) {
                                db.collection("users").document(myUid)
                                  .set(java.util.Collections.singletonMap("ecPublicKey", newPub),
                                       SetOptions.merge());
                            }
                        }
                        SecretKey sk  = ECDHHelper.deriveSharedKey(myPriv, partnerPub);
                        String    b64 = android.util.Base64.encodeToString(
                                sk.getEncoded(), android.util.Base64.NO_WRAP);
                        secure.edit()
                              .putString(CryptoInitializer.KEY_SHARED_AES,        b64)
                              .putString(CryptoInitializer.KEY_PARTNER_EC_PUBLIC,  partnerPub)
                              .apply();
                        Log.d(TAG, "ECDH shared key (re-)derived successfully");
                    } catch (Exception ex) {
                        Log.w(TAG, "ECDH re-derive failed: " + ex.getMessage());
                    } finally {
                        runOnUiThread(this::listenForMessages);
                    }
                });
            })
            .addOnFailureListener(e -> {
                Log.w(TAG, "Could not fetch partner key from Firestore: " + e.getMessage());
                listenForMessages();
            });
    }

    /**
     * Marks own last-seen timestamp AND batch-updates all of the partner's
     * messages in this chat to "read" so the sender sees blue double-ticks.
     */
    private void markMessagesAsReadAndSeen() {
        if (conversationId == null || myUid == null) return;

        // Update lastSeen timestamp
        db.collection("chats").document(conversationId)
          .update("lastSeen_" + myUid, FieldValue.serverTimestamp());

        if (partnerUid == null) return;

        // Batch-update partner's sent/delivered messages → "read"
        db.collection("chats").document(conversationId)
          .collection("messages")
          .whereEqualTo("sender", partnerUid)
          .get()
          .addOnSuccessListener(snaps -> {
              if (snaps.isEmpty()) return;
              WriteBatch batch = db.batch();
              int count = 0;
              for (DocumentSnapshot doc : snaps.getDocuments()) {
                  String st = doc.getString("status");
                  if ("read".equals(st)) continue;
                  batch.update(doc.getReference(), "status", "read");
                  if (++count == 450) { batch.commit(); batch = db.batch(); count = 0; }
              }
              if (count > 0) batch.commit();
          });
    }

    private void saveToRoom(Message m) {
        dbExecutor.execute(() -> AppDatabase.getInstance(this).messageDao().insert(m));
    }

    /**
     * Pings the partner via Firestore so the Cloud Function (notifyOnMessage) can
     * deliver the FCM push. We also write a "nudge" timestamp so the receiver
     * device wakes its Firestore listener even while backgrounded.
     *
     * The heavy lifting (FCM send) is done server-side by the Cloud Function that
     * triggers on every new message document creation — no service-account.json
     * needed in the APK.
     */
    private void notifyPartner(String title, String body) {
        if (conversationId == null || partnerUid == null) return;
        // Write a lightweight nudge to the chat doc. The Cloud Function already handles
        // the actual FCM push when the message document is created, so this is just a
        // belt-and-suspenders update to ensure lastActivity is current.
        db.collection("chats").document(conversationId)
          .update("lastActivity", com.google.firebase.firestore.FieldValue.serverTimestamp())
          .addOnFailureListener(e -> Log.w(TAG, "nudge update failed (non-critical): " + e.getMessage()));
    }

}
