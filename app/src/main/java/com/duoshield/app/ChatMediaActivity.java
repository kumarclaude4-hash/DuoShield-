package com.duoshield.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.core.app.NotificationManagerCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.duoshield.app.crypto.CryptoHelper;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.crypto.KeyManager;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.models.Message;
// BiometricHelper removed from direct use here — lock handled by BaseActivity
import com.duoshield.app.ui.MessageAdapter;
import com.duoshield.app.ui.SettingsActivity;
import com.duoshield.app.ui.WaveformView;
import com.duoshield.app.util.VoiceMessagePlayer;
import com.duoshield.app.util.VoiceRecorderHelper;
import com.google.firebase.auth.FirebaseAuth;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import com.google.firebase.firestore.DocumentChange;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private static final String TAG          = "ChatMediaActivity";
    private static final String FCM_ENDPOINT =
            "https://fcm.googleapis.com/v1/projects/duoshield-8caf1/messages:send";
    private static final String FCM_SCOPE    =
            "https://www.googleapis.com/auth/firebase.messaging";
    private static final int    MAX_PINS     = 3;

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
    private StorageReference  storageRef;

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
        myUid          = prefs.getString("my_uid", FirebaseAuth.getInstance().getUid());
        partnerUid     = prefs.getString("partner_uid", null);

        if (conversationId == null) {
            Toast.makeText(this, "No active conversation. Please pair first.", Toast.LENGTH_LONG).show();
            finish(); return;
        }

        // ── Header ──────────────────────────────────────────────────
        ivPartnerAvatar  = findViewById(R.id.ivPartnerAvatar);
        tvAvatarInitial  = findViewById(R.id.tvAvatarInitial);
        tvPartnerName    = findViewById(R.id.tvPartnerName);
        tvOnlineStatus   = findViewById(R.id.tvOnlineStatus);
        headerOnlineDot  = findViewById(R.id.headerOnlineDot);

        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> onBackPressed());

        ImageView btnFingerprint = findViewById(R.id.btnFingerprint);
        btnFingerprint.setOnClickListener(v ->
            startActivity(new Intent(this, KeyFingerprintActivity.class)));

        ImageView btnOverflow = findViewById(R.id.btnOverflow);
        btnOverflow.setOnClickListener(v -> {
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

        adapter = new MessageAdapter(new ArrayList<>(), myUid, this::onVoicePlay,
            (msg, anchor) -> showMessageActionDialog(msg));
        LinearLayoutManager llm = new LinearLayoutManager(this);
        llm.setStackFromEnd(true);
        recyclerView.setLayoutManager(llm);
        recyclerView.setAdapter(adapter);

        db         = FirebaseFirestore.getInstance();
        storageRef = FirebaseStorage.getInstance().getReference();

        applyWallpaper();
        loadPartnerInfo();
        listenForMessages();
        listenForConvUpdates();

        sendButton.setOnClickListener(v -> {
            String text = messageInput.getText().toString().trim();
            if (!text.isEmpty()) { sendMessage(text); messageInput.setText(""); }
        });
        uploadButton.setOnClickListener(v -> showMediaTypePopup());
        micButton.setOnClickListener(v -> startVoiceRecording());
        cancelReplyBtn.setOnClickListener(v -> clearReplyMode());
        cancelRecordingBtn.setOnClickListener(v -> cancelVoiceRecording());
        stopRecordingBtn.setOnClickListener(v -> stopAndSendVoiceRecording());

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

        pinnedBanner.setOnClickListener(v -> cycleAndScrollToPin());
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
        Uri fileUri = Uri.fromFile(f);
        StorageReference ref = storageRef.child("voice").child(conversationId)
            .child(UUID.randomUUID() + ".3gp");
        runOnUiThread(() -> { uploadProgress.setVisibility(View.VISIBLE); uploadProgress.setProgress(0); });
        ref.putFile(fileUri)
            .addOnProgressListener(s -> {
                int pct = (int) (100.0 * s.getBytesTransferred() / s.getTotalByteCount());
                runOnUiThread(() -> uploadProgress.setProgress(pct));
            })
            .addOnSuccessListener(s -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
                runOnUiThread(() -> uploadProgress.setVisibility(View.GONE));
                sendVoiceMessage(uri.toString());
                f.delete();
            }))
            .addOnFailureListener(e -> runOnUiThread(() -> {
                uploadProgress.setVisibility(View.GONE);
                Toast.makeText(this, "Voice upload failed", Toast.LENGTH_SHORT).show();
            }));
    }

    private void sendVoiceMessage(String mediaUrl) {
        String msgId = UUID.randomUUID().toString();
        long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", "");
        doc.put("mediaUrl", mediaUrl); doc.put("mediaType", "voice");
        doc.put("type", "voice"); doc.put("isEncrypted", false);
        doc.put("expiresAt", exp); doc.put("timestamp", FieldValue.serverTimestamp());
        db.collection("conversations").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              Message m = new Message(msgId, conversationId, myUid, "", now, false, mediaUrl, "voice");
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

        player.play(msg.getMediaUrl(), new VoiceMessagePlayer.PlayerListener() {
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
        });
    }

    // ══════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════

    @Override protected void onResume() {
        super.onResume();
        markAsSeen();
        clearBadge();
        applyWallpaper();
    }

    @Override protected void onStop() {
        super.onStop();
        if (msgListener  != null) { msgListener.remove();  msgListener  = null; }
        if (convListener != null) { convListener.remove(); convListener = null; }
        typingHandler.removeCallbacksAndMessages(null);
        recordingTimerHandler.removeCallbacks(timerTick);
        player.release();
        if (isTyping && conversationId != null && myUid != null) {
            db.collection("conversations").document(conversationId)
              .update("typing_" + myUid, false);
            isTyping = false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // FIRESTORE LISTENERS
    // ══════════════════════════════════════════════════════════════

    private void listenForConvUpdates() {
        convListener = db.collection("conversations").document(conversationId)
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
        msgListener = db.collection("conversations").document(conversationId)
          .collection("messages").orderBy("timestamp")
          .addSnapshotListener((snaps, e) -> {
              if (snaps == null) return;
              boolean changed = false;
              for (DocumentChange dc : snaps.getDocumentChanges()) {
                  if (dc.getType() == DocumentChange.Type.ADDED) {
                      String id    = dc.getDocument().getString("id");
                      String convo = dc.getDocument().getString("conversationId");
                      String from  = dc.getDocument().getString("sender");
                      String text  = dc.getDocument().getString("text");
                      String mUrl  = dc.getDocument().getString("mediaUrl");
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
                          // Skip if already in local list
                          boolean exists = false;
                          for (Message m : adapter.getMessages())
                              if (id.equals(m.getId())) { exists = true; break; }
                          if (exists) continue;

                          Message m = new Message(id, convo, from, text, ts, false, mUrl, mType);
                          if (rpId  != null) m.setReplyToId(rpId);
                          if (rpPrv != null) m.setReplyPreview(rpPrv);
                          if (expAt != null) m.setExpiresAt(expAt);
                          if (isExpired(m)) continue;

                          adapter.appendMessage(m);
                          saveToRoom(m);
                          changed = true;
                      }
                  } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                      String id       = dc.getDocument().getString("id");
                      String reaction = dc.getDocument().getString("reaction");
                      if (id != null) {
                          String finalId = id; String finalReaction = reaction;
                          adapter.updateMessage(id, msg -> msg.setReaction(finalReaction));
                      }
                  }
              }
              if (changed) {
                  int last = adapter.getItemCount() - 1;
                  if (last >= 0) recyclerView.scrollToPosition(last);
              }
          });
    }

    // ══════════════════════════════════════════════════════════════
    // PINNING
    // ══════════════════════════════════════════════════════════════

    private void refreshPinnedBanner() {
        if (pinnedList.isEmpty()) { pinnedBanner.setVisibility(View.GONE); return; }
        pinnedBanner.setVisibility(View.VISIBLE);
        if (pinnedViewIdx >= pinnedList.size()) pinnedViewIdx = 0;
        Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
        Object preview = pin.get("preview");
        pinnedText.setText(preview != null ? preview.toString() : "Pinned message");
        pinnedCount.setText(pinnedList.size() > 1 ? (pinnedViewIdx + 1) + "/" + pinnedList.size() : "");
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
        db.collection("conversations").document(conversationId)
          .update("pinnedMessages", FieldValue.arrayUnion(entry))
          .addOnSuccessListener(v -> Toast.makeText(this, "Pinned 📌", Toast.LENGTH_SHORT).show())
          .addOnFailureListener(ex -> {
              Map<String, Object> d = new HashMap<>(); d.put("pinnedMessages", Arrays.asList(entry));
              db.collection("conversations").document(conversationId)
                .set(d, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener(v2 -> Toast.makeText(this, "Pinned 📌", Toast.LENGTH_SHORT).show());
          });
    }

    private void unpinMessage(Message msg) {
        Map<String, Object> toRemove = null;
        for (Map<String, Object> p : pinnedList) if (msg.getId().equals(p.get("id"))) { toRemove = p; break; }
        if (toRemove == null) { Toast.makeText(this, "Not pinned", Toast.LENGTH_SHORT).show(); return; }
        db.collection("conversations").document(conversationId)
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
                db.collection("conversations").document(conversationId)
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
            db.collection("conversations").document(conversationId).update("typing_" + myUid, true);
        }
        typingHandler.removeCallbacksAndMessages(null);
        typingHandler.postDelayed(() -> {
            isTyping = false;
            db.collection("conversations").document(conversationId).update("typing_" + myUid, false);
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
        String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
        StorageReference ref = storageRef.child("media").child(conversationId).child(UUID.randomUUID() + ext);
        uploadProgress.setVisibility(View.VISIBLE); uploadProgress.setProgress(0);
        ref.putFile(fileUri)
            .addOnProgressListener(s -> uploadProgress.setProgress(
                (int) (100.0 * s.getBytesTransferred() / s.getTotalByteCount())))
            .addOnFailureListener(e -> { Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show(); uploadProgress.setVisibility(View.GONE); })
            .addOnSuccessListener(s -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
                uploadProgress.setVisibility(View.GONE);
                sendMediaMessage(uri.toString(), mediaType);
            }).addOnFailureListener(e -> uploadProgress.setVisibility(View.GONE)));
    }

    private void sendMediaMessage(String mediaUrl, String mediaType) {
        String msgId = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", "");
        doc.put("mediaUrl", mediaUrl); doc.put("mediaType", mediaType);
        doc.put("isEncrypted", false); doc.put("type", mediaType);
        doc.put("expiresAt", exp); doc.put("timestamp", FieldValue.serverTimestamp());
        db.collection("conversations").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              Message m = new Message(msgId, conversationId, myUid, "", now, false, mediaUrl, mediaType);
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
        db.collection("conversations").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              saveToRoom(new Message(msgId, conversationId, myUid, cardText, now, false, null, "contact_card"));
              notifyPartner("DuoShield", "Shared a contact card");
          });
    }

    private void sendMessage(String plaintext) {
        String ciphertext;
        try {
            SecretKey k = CryptoInitializer.getSharedKey(this);
            if (k == null) k = KeyManager.getKey();
            if (k == null) {
                KeyManager.generateKey();
                k = KeyManager.getKey();
            }
            if (k == null) throw new IllegalStateException("No encryption key available");
            ciphertext = CryptoHelper.encrypt(plaintext, k);
        } catch (Exception e) {
            Log.e(TAG, "Encryption failed", e);
            Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show(); return;
        }
        String msgId = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        long exp = getDisappearMs() > 0 ? now + getDisappearMs() : 0;
        String rId = pendingReplyId; String rPrv = pendingReplyPreview;
        clearReplyMode();
        Map<String, Object> doc = new HashMap<>();
        doc.put("id", msgId); doc.put("conversationId", conversationId);
        doc.put("sender", myUid); doc.put("text", ciphertext);
        doc.put("isEncrypted", true); doc.put("type", "text");
        doc.put("expiresAt", exp);
        if (rId != null) { doc.put("replyToId", rId); doc.put("replyPreview", rPrv); }
        doc.put("timestamp", FieldValue.serverTimestamp());
        final String fc = ciphertext;
        db.collection("conversations").document(conversationId)
          .collection("messages").document(msgId).set(doc)
          .addOnSuccessListener(v -> {
              Message m = new Message(msgId, conversationId, myUid, fc, now, true);
              m.setExpiresAt(exp);
              if (rId != null) { m.setReplyToId(rId); m.setReplyPreview(rPrv); }
              saveToRoom(m);
              notifyPartner("DuoShield", "New message");
          })
          .addOnFailureListener(e -> Toast.makeText(this, "Failed to send.", Toast.LENGTH_SHORT).show());
    }

    private void markAsSeen() {
        if (conversationId == null || myUid == null) return;
        db.collection("conversations").document(conversationId)
          .update("lastSeen_" + myUid, FieldValue.serverTimestamp());
    }

    private void saveToRoom(Message m) {
        dbExecutor.execute(() -> AppDatabase.getInstance(this).messageDao().insert(m));
    }

    private void notifyPartner(String title, String body) {
        if (partnerUid == null) return;
        db.collection("users").document(partnerUid).get()
          .addOnSuccessListener(doc -> {
              String tok = doc.getString("fcmToken");
              if (tok != null && !tok.isEmpty()) sendFcmNotification(tok, title, body);
          });
    }

    private void sendFcmNotification(String token, String title, String body) {
        dbExecutor.execute(() -> {
            try {
                JSONObject notification = new JSONObject();
                notification.put("title", title); notification.put("body", body);
                JSONObject msgObj = new JSONObject();
                msgObj.put("token", token); msgObj.put("notification", notification);
                JSONObject payload = new JSONObject(); payload.put("message", msgObj);

                // Read service account for JWT signing (no google-auth-library needed)
                InputStream sa = getAssets().open("service-account.json");
                byte[] saBytes = new byte[sa.available()];
                //noinspection ResultOfMethodCallIgnored
                sa.read(saBytes);
                sa.close();
                JSONObject saJson = new JSONObject(new String(saBytes, StandardCharsets.UTF_8));
                String pemKey     = saJson.getString("private_key");
                String clientEmail = saJson.getString("client_email");

                String accessToken = buildOAuth2Token(pemKey, clientEmail);

                URL url = new URL(FCM_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                }
                Log.d(TAG, "FCM: " + conn.getResponseCode());
            } catch (Exception ex) { Log.w(TAG, "FCM failed", ex); }
        });
    }

    /**
     * Builds a short-lived OAuth2 access token for FCM HTTP v1 by manually
     * signing a JWT with RS256 — no external google-auth library required.
     * minSdk=26 guarantees {@link java.util.Base64} and {@link java.security.Signature}.
     */
    private String buildOAuth2Token(String privateKeyPem, String clientEmail) throws Exception {
        // Strip PEM envelope and decode to PKCS8 bytes
        String pem = privateKeyPem
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replaceAll("\\s+", "");
        byte[] keyBytes = java.util.Base64.getDecoder().decode(pem);
        PrivateKey privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(new PKCS8EncodedKeySpec(keyBytes));

        // Build JWT header.claims (RFC 7519 / Google OAuth2 service-account flow)
        long now = System.currentTimeMillis() / 1000;
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}"
                .getBytes(StandardCharsets.UTF_8));
        String claims = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(("{\"iss\":\"" + clientEmail + "\","
                + "\"scope\":\"" + FCM_SCOPE + "\","
                + "\"aud\":\"https://oauth2.googleapis.com/token\","
                + "\"iat\":" + now + ","
                + "\"exp\":" + (now + 3600) + "}")
                .getBytes(StandardCharsets.UTF_8));
        String sigInput = header + "." + claims;

        // Sign with RS256
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(sigInput.getBytes(StandardCharsets.UTF_8));
        String sigB64 = java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signer.sign());

        String jwt = sigInput + "." + sigB64;

        // Exchange JWT for access token
        URL tokenUrl = new URL("https://oauth2.googleapis.com/token");
        HttpURLConnection tc = (HttpURLConnection) tokenUrl.openConnection();
        tc.setRequestMethod("POST");
        tc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        tc.setDoOutput(true);
        String form = "grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer"
                    + "&assertion=" + jwt;
        try (OutputStream os = tc.getOutputStream()) {
            os.write(form.getBytes(StandardCharsets.UTF_8));
        }
        byte[] resp = new byte[4096];
        int read = tc.getInputStream().read(resp);
        return new JSONObject(new String(resp, 0, read, StandardCharsets.UTF_8))
            .getString("access_token");
    }
}

