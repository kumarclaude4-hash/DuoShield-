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
  import android.view.Menu;
  import android.view.MenuItem;
  import android.view.View;
  import android.widget.Button;
  import android.widget.EditText;
  import android.widget.ImageView;
  import android.widget.LinearLayout;
  import android.widget.ProgressBar;
  import android.widget.TextView;
  import android.widget.Toast;
  import androidx.activity.result.ActivityResultLauncher;
  import androidx.activity.result.contract.ActivityResultContracts;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.appcompat.widget.Toolbar;
  import androidx.core.app.NotificationManagerCompat;
  import androidx.recyclerview.widget.LinearLayoutManager;
  import androidx.recyclerview.widget.RecyclerView;

  import com.bumptech.glide.Glide;
  import com.duoshield.app.crypto.CryptoHelper;
  import com.duoshield.app.crypto.CryptoInitializer;
  import com.duoshield.app.crypto.KeyManager;
  import com.duoshield.app.db.AppDatabase;
  import com.duoshield.app.models.Message;
  import com.duoshield.app.security.BiometricHelper;
  import com.duoshield.app.ui.MessageAdapter;
  import com.duoshield.app.ui.SettingsActivity;
  import com.google.auth.oauth2.GoogleCredentials;
  import com.google.firebase.auth.FirebaseAuth;
  import com.google.firebase.firestore.DocumentChange;
  import com.google.firebase.firestore.FieldValue;
  import com.google.firebase.firestore.FirebaseFirestore;
  import com.google.firebase.firestore.ListenerRegistration;
  import com.google.firebase.storage.FirebaseStorage;
  import com.google.firebase.storage.StorageReference;

  import org.json.JSONObject;

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

      // ── Pinned messages ──────────────────────────────────────────
      // Each entry: { "id": msgId, "preview": "..." }
      private List<Map<String, Object>> pinnedList = new ArrayList<>();
      private int                       pinnedViewIdx = 0; // which pin is shown in banner

      private FirebaseFirestore db;
      private StorageReference  storageRef;
      private EditText          messageInput;
      private Button            sendButton;
      private ImageView         uploadButton;
      private ProgressBar       uploadProgress;
      private RecyclerView      recyclerView;
      private TextView          typingIndicator;
      private View              replyPreviewBar;
      private TextView          replyPreviewBarText;
      private ImageView         cancelReplyBtn;

      // Pinned banner views
      private LinearLayout      pinnedBanner;
      private TextView          pinnedText, pinnedCount;
      private ImageView         pinnedCloseBtn;

      private List<Message>     messages;
      private MessageAdapter    adapter;

      private String conversationId;
      private String myUid;
      private String partnerUid;

      private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

      private ListenerRegistration msgListener;
      private ListenerRegistration convListener;

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
                  Toast.makeText(this, "Wallpaper set!", Toast.LENGTH_SHORT).show();
              }
          });

      @Override
      protected void onCreate(Bundle savedInstanceState) {
          super.onCreate(savedInstanceState);
          SharedPreferences p = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
          if (p.getBoolean("biometric_enabled", false)) {
              BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
                  @Override public void onSuccess() { setupChat(); }
                  @Override public void onFailure() { finish(); }
              });
          } else {
              setupChat();
          }
      }

      private void setupChat() {
          setContentView(R.layout.activity_chat_media);
          Toolbar toolbar = findViewById(R.id.toolbar);
          setSupportActionBar(toolbar);
          if (getSupportActionBar() != null) getSupportActionBar().setTitle("DuoShield");

          SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
          conversationId = prefs.getString("conversation_id", null);
          myUid          = prefs.getString("my_uid", FirebaseAuth.getInstance().getUid());
          partnerUid     = prefs.getString("partner_uid", null);

          if (conversationId == null) {
              Toast.makeText(this, "No active conversation. Please pair first.", Toast.LENGTH_LONG).show();
              finish(); return;
          }

          messageInput        = findViewById(R.id.etMessage);
          sendButton          = findViewById(R.id.btnSend);
          uploadButton        = findViewById(R.id.btnAttach);
          uploadProgress      = null; // not in new layout
          recyclerView        = findViewById(R.id.recyclerMessages);
          typingIndicator     = null; // not in new layout
          replyPreviewBar     = findViewById(R.id.replyPreviewBar);
          replyPreviewBarText = findViewById(R.id.tvReplyPreview);
          cancelReplyBtn      = findViewById(R.id.btnCancelReply);
          pinnedBanner        = findViewById(R.id.pinnedStrip);
          pinnedText          = findViewById(R.id.tvPinnedPreview);
          pinnedCount         = null; // not in new layout
          pinnedCloseBtn      = findViewById(R.id.btnClosePin);

          messages = new ArrayList<>();
          adapter  = new MessageAdapter(messages, myUid, null,
              (msg, anchor) -> showMessageActionDialog(msg));
          recyclerView.setAdapter(adapter);
          recyclerView.setLayoutManager(new LinearLayoutManager(this));

          db         = FirebaseFirestore.getInstance();
          storageRef = FirebaseStorage.getInstance().getReference();

          applyWallpaper();
          listenForMessages();
          listenForPartnerSeen();
          listenForTyping();
          listenForPins();       // pin banner

          sendButton.setOnClickListener(v -> {
              String text = messageInput.getText().toString().trim();
              if (!text.isEmpty()) { sendMessage(text); messageInput.setText(""); }
          });
          uploadButton.setOnClickListener(v -> showMediaTypePopup());
          cancelReplyBtn.setOnClickListener(v -> clearReplyMode());

          messageInput.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
              @Override public void onTextChanged(CharSequence s, int st, int b, int c) { onUserTyping(); }
              @Override public void afterTextChanged(Editable s) {}
          });

          // Pinned banner: tap = cycle through pins / scroll to message; X = dismiss banner
          pinnedBanner.setOnClickListener(v -> cycleAndScrollToPin());
          pinnedCloseBtn.setOnClickListener(v -> pinnedBanner.setVisibility(View.GONE));
      }

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
          if (isTyping && conversationId != null && myUid != null) {
              db.collection("conversations").document(conversationId)
                .update("typing_" + myUid, false);
              isTyping = false;
          }
      }

      // ══════════════════════════════════════════════════════════════
      // MESSAGE PINNING
      // ══════════════════════════════════════════════════════════════

      private void listenForPins() {
          convListener = db.collection("conversations").document(conversationId)
            .addSnapshotListener((snap, e) -> {
                if (snap == null) return;

                // Pinned list is stored as an array of maps: [{id, preview}]
                List<Map<String, Object>> raw =
                    (List<Map<String, Object>>) snap.get("pinnedMessages");
                pinnedList = raw != null ? raw : new ArrayList<>();

                // Update adapter pin highlights
                Set<String> ids = new HashSet<>();
                for (Map<String, Object> m : pinnedList) {
                    Object id = m.get("id");
                    if (id instanceof String) ids.add((String) id);
                }
                adapter.updatePinnedIds(ids);

                // Update banner
                refreshPinnedBanner();

                // Also handle typing from this listener (reuse single snapshot listener)
                Object val = snap.get("typing_" + partnerUid);
                if (typingIndicator != null)
                    typingIndicator.setVisibility(Boolean.TRUE.equals(val) ? View.VISIBLE : View.GONE);
            });
      }

      private void refreshPinnedBanner() {
          if (pinnedList.isEmpty()) {
              pinnedBanner.setVisibility(View.GONE);
              return;
          }
          pinnedBanner.setVisibility(View.VISIBLE);
          // Clamp index
          if (pinnedViewIdx >= pinnedList.size()) pinnedViewIdx = 0;
          Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
          Object preview = pin.get("preview");
          pinnedText.setText(preview != null ? preview.toString() : "Pinned message");
          if (pinnedList.size() > 1)
              pinnedCount.setText((pinnedViewIdx + 1) + "/" + pinnedList.size());
          else
              pinnedCount.setText("");
      }

      private void cycleAndScrollToPin() {
          if (pinnedList.isEmpty()) return;
          pinnedViewIdx = (pinnedViewIdx + 1) % pinnedList.size();
          refreshPinnedBanner();
          // Scroll to the message in the list
          Map<String, Object> pin = pinnedList.get(pinnedViewIdx);
          Object targetId = pin.get("id");
          if (targetId == null) return;
          for (int i = 0; i < messages.size(); i++) {
              if (targetId.toString().equals(messages.get(i).getId())) {
                  recyclerView.smoothScrollToPosition(i);
                  break;
              }
          }
      }

      private void pinMessage(Message msg) {
          // Check if already pinned
          for (Map<String, Object> p : pinnedList) {
              if (msg.getId().equals(p.get("id"))) {
                  Toast.makeText(this, "Already pinned", Toast.LENGTH_SHORT).show();
                  return;
              }
          }
          if (pinnedList.size() >= MAX_PINS) {
              Toast.makeText(this, "Max " + MAX_PINS + " pins reached. Unpin one first.", Toast.LENGTH_SHORT).show();
              return;
          }
          String preview = (msg.getText() != null && !msg.getText().isEmpty())
              ? msg.getText() : "[media]";
          Map<String, Object> entry = new HashMap<>();
          entry.put("id",      msg.getId());
          entry.put("preview", preview);

          db.collection("conversations").document(conversationId)
            .update("pinnedMessages", FieldValue.arrayUnion(entry))
            .addOnSuccessListener(v -> Toast.makeText(this, "Message pinned 📌", Toast.LENGTH_SHORT).show())
            .addOnFailureListener(ex -> {
                // Field may not exist yet — set it
                Map<String, Object> initDoc = new HashMap<>();
                initDoc.put("pinnedMessages", Arrays.asList(entry));
                db.collection("conversations").document(conversationId)
                  .set(initDoc, com.google.firebase.firestore.SetOptions.merge())
                  .addOnSuccessListener(v2 -> Toast.makeText(this, "Message pinned 📌", Toast.LENGTH_SHORT).show());
            });
      }

      private void unpinMessage(Message msg) {
          Map<String, Object> toRemove = null;
          for (Map<String, Object> p : pinnedList) {
              if (msg.getId().equals(p.get("id"))) { toRemove = p; break; }
          }
          if (toRemove == null) { Toast.makeText(this, "Not pinned", Toast.LENGTH_SHORT).show(); return; }
          db.collection("conversations").document(conversationId)
            .update("pinnedMessages", FieldValue.arrayRemove(toRemove))
            .addOnSuccessListener(v -> Toast.makeText(this, "Unpinned", Toast.LENGTH_SHORT).show());
      }

      private boolean isPinned(Message msg) {
          for (Map<String, Object> p : pinnedList) {
              if (msg.getId().equals(p.get("id"))) return true;
          }
          return false;
      }

      // ══════════════════════════════════════════════════════════════
      // MESSAGE ACTION DIALOG (long press) — includes Pin/Unpin
      // ══════════════════════════════════════════════════════════════

      private void showMessageActionDialog(Message msg) {
          boolean pinned = isPinned(msg);
          String[] options = {
              pinned ? "📌 Unpin" : "📌 Pin",
              "↩ Reply",
              "😀 React",
              "🗑 Delete (local)"
          };
          new AlertDialog.Builder(this)
              .setItems(options, (d, which) -> {
                  switch (which) {
                      case 0: if (pinned) unpinMessage(msg); else pinMessage(msg); break;
                      case 1: enterReplyMode(msg); break;
                      case 2: showReactionPicker(msg); break;
                      case 3: deleteMessageLocally(msg); break;
                  }
              }).show();
      }

      // ══════════════════════════════════════════════════════════════
      // TYPING INDICATOR
      // ══════════════════════════════════════════════════════════════

      private void onUserTyping() {
          if (!isTyping) {
              isTyping = true;
              db.collection("conversations").document(conversationId)
                .update("typing_" + myUid, true);
          }
          typingHandler.removeCallbacksAndMessages(null);
          typingHandler.postDelayed(() -> {
              isTyping = false;
              db.collection("conversations").document(conversationId)
                .update("typing_" + myUid, false);
          }, 3000);
      }

      private void listenForTyping() {
          // Typing is now handled inside listenForPins() snapshot listener — no-op here
      }

      // ══════════════════════════════════════════════════════════════
      // REPLY MODE
      // ══════════════════════════════════════════════════════════════

      private void enterReplyMode(Message msg) {
          pendingReplyId      = msg.getId();
          pendingReplyPreview = (msg.getText() != null && !msg.getText().isEmpty())
              ? msg.getText() : "[media]";
          replyPreviewBarText.setText("Replying to: " + pendingReplyPreview);
          replyPreviewBar.setVisibility(View.VISIBLE);
          messageInput.requestFocus();
      }

      private void clearReplyMode() {
          pendingReplyId = null; pendingReplyPreview = null;
          replyPreviewBar.setVisibility(View.GONE);
      }

      // ══════════════════════════════════════════════════════════════
      // REACTIONS
      // ══════════════════════════════════════════════════════════════

      private void showReactionPicker(Message msg) {
          String[] emojis = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
          new AlertDialog.Builder(this).setTitle("React")
              .setItems(emojis, (d, w) -> sendReaction(msg, emojis[w])).show();
      }

      private void sendReaction(Message msg, String emoji) {
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msg.getId())
            .update("reaction", emoji);
          msg.setReaction(emoji);
          int idx = messages.indexOf(msg);
          if (idx >= 0) adapter.notifyItemChanged(idx);
      }

      private void deleteMessageLocally(Message msg) {
          int idx = messages.indexOf(msg);
          if (idx >= 0) { messages.remove(idx); adapter.notifyItemRemoved(idx); }
      }

      // ══════════════════════════════════════════════════════════════
      // DISAPPEARING MESSAGES
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
                  recyclerView.setBackgroundColor(prefs.getInt("wallpaper_color", Color.WHITE)); break;
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
      // MENU
      // ══════════════════════════════════════════════════════════════

      @Override public boolean onCreateOptionsMenu(Menu menu) {
          getMenuInflater().inflate(R.menu.chat_menu, menu); return true;
      }

      @Override public boolean onOptionsItemSelected(MenuItem item) {
          int id = item.getItemId();
          if (id == R.id.action_settings) { startActivity(new Intent(this, SettingsActivity.class)); return true; }
          if (id == R.id.action_wallpaper) { showWallpaperDialog(); return true; }
          return super.onOptionsItemSelected(item);
      }

      // ══════════════════════════════════════════════════════════════
      // CORE: SEND / LISTEN
      // ══════════════════════════════════════════════════════════════

      private void showMediaTypePopup() {
          new AlertDialog.Builder(this).setTitle("Share")
              .setItems(new String[]{"Image", "Video", "Profile Card"}, (d, w) -> {
                  if      (w == 0) pickImageLauncher.launch("image/*");
                  else if (w == 1) pickVideoLauncher.launch("video/*");
                  else             sendContactCard();
              }).show();
      }

      private void uploadMedia(Uri fileUri, String mediaType) {
          String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
          StorageReference ref = storageRef.child("media").child(conversationId).child(UUID.randomUUID() + ext);
          uploadProgress.setVisibility(View.VISIBLE); uploadProgress.setProgress(0);
          uploadButton.setEnabled(false);
          ref.putFile(fileUri)
              .addOnProgressListener(s -> uploadProgress.setProgress(
                  (int) (100.0 * s.getBytesTransferred() / s.getTotalByteCount())))
              .addOnFailureListener(e -> {
                  Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show();
                  uploadProgress.setVisibility(View.GONE); uploadButton.setEnabled(true);
              })
              .addOnSuccessListener(s -> ref.getDownloadUrl().addOnSuccessListener(uri -> {
                  uploadProgress.setVisibility(View.GONE); uploadButton.setEnabled(true);
                  sendMediaMessage(uri.toString(), mediaType);
              }).addOnFailureListener(e -> {
                  uploadProgress.setVisibility(View.GONE); uploadButton.setEnabled(true);
              }));
      }

      private void sendMediaMessage(String mediaUrl, String mediaType) {
          String msgId = UUID.randomUUID().toString();
          long   now   = System.currentTimeMillis();
          long   ttl   = getDisappearMs();
          long   exp   = ttl > 0 ? now + ttl : 0;
          Map<String, Object> doc = new HashMap<>();
          doc.put("id", msgId); doc.put("conversationId", conversationId);
          doc.put("sender", myUid); doc.put("text", "");
          doc.put("mediaUrl", mediaUrl); doc.put("mediaType", mediaType);
          doc.put("isEncrypted", false); doc.put("type", mediaType);
          doc.put("expiresAt", exp); doc.put("timestamp", FieldValue.serverTimestamp());
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msgId).set(doc)
            .addOnSuccessListener(v -> {
                Message m = new Message(); m.setId(msgId); m.setConversationId(conversationId); m.setSender(myUid); m.setText(""); m.setTimestamp(now); m.setEncrypted(false); m.setMediaUrl(mediaUrl); m.setMediaType(mediaType);
                m.setExpiresAt(exp); saveToRoom(m);
                notifyPartner("DuoShield", "video".equals(mediaType) ? "Sent a video" : "Sent an image");
            });
      }

      private void sendContactCard() {
          String cardText = "DuoShield User|" + myUid;
          String msgId    = UUID.randomUUID().toString();
          long   now      = System.currentTimeMillis();
          Map<String, Object> doc = new HashMap<>();
          doc.put("id", msgId); doc.put("conversationId", conversationId);
          doc.put("sender", myUid); doc.put("text", cardText);
          doc.put("mediaType", "contact_card"); doc.put("type", "contact_card");
          doc.put("isEncrypted", false); doc.put("timestamp", FieldValue.serverTimestamp());
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msgId).set(doc)
            .addOnSuccessListener(v -> {
                { Message mc = new Message(); mc.setId(msgId); mc.setConversationId(conversationId); mc.setSender(myUid); mc.setText(cardText); mc.setTimestamp(now); mc.setEncrypted(false); mc.setMediaType("contact_card"); saveToRoom(mc); }
                notifyPartner("DuoShield", "Shared a profile card");
            });
      }

      private void sendMessage(String plaintext) {
          String ciphertext;
          try {
              SecretKey k = CryptoInitializer.getSharedKey(this);
              if (k == null) k = KeyManager.getKey();
              ciphertext = CryptoHelper.encrypt(plaintext, k);
          } catch (Exception e) {
              Log.e(TAG, "Encryption failed", e);
              Toast.makeText(this, "Encryption error", Toast.LENGTH_SHORT).show(); return;
          }
          String msgId   = UUID.randomUUID().toString();
          long   now     = System.currentTimeMillis();
          long   ttl     = getDisappearMs();
          long   exp     = ttl > 0 ? now + ttl : 0;
          String rId     = pendingReplyId;
          String rPrv    = pendingReplyPreview;
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
                Message m = new Message(); m.setId(msgId); m.setConversationId(conversationId); m.setSender(myUid); m.setText(fc); m.setTimestamp(now); m.setEncrypted(true);
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

      private void listenForPartnerSeen() {
          // Note: partner seen is handled inside listenForPins() via same snapshot listener
      }

      private void listenForMessages() {
          msgListener = db.collection("conversations").document(conversationId)
            .collection("messages").orderBy("timestamp")
            .addSnapshotListener((snaps, e) -> {
                if (snaps == null) return;
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
                        if (id != null) {
                            Message m = new Message(); m.setId(id); m.setConversationId(convo); m.setSender(from); m.setText(text); m.setTimestamp(ts); m.setEncrypted(false); m.setMediaUrl(mUrl); m.setMediaType(mType);
                            if (rpId  != null) m.setReplyToId(rpId);
                            if (rpPrv != null) m.setReplyPreview(rpPrv);
                            if (expAt != null) m.setExpiresAt(expAt);
                            if (isExpired(m)) continue;
                            messages.add(m);
                            adapter.notifyItemInserted(messages.size() - 1);
                            recyclerView.scrollToPosition(messages.size() - 1);
                            saveToRoom(m);
                        }
                    } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                        String id       = dc.getDocument().getString("id");
                        String reaction = dc.getDocument().getString("reaction");
                        for (int i = 0; i < messages.size(); i++) {
                            if (messages.get(i).getId().equals(id)) {
                                messages.get(i).setReaction(reaction);
                                adapter.notifyItemChanged(i); break;
                            }
                        }
                    }
                }
            });
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
                  InputStream sa = getAssets().open("service-account.json");
                  GoogleCredentials creds = GoogleCredentials.fromStream(sa)
                      .createScoped(Arrays.asList(FCM_SCOPE));
                  creds.refreshIfExpired();
                  URL url = new URL(FCM_ENDPOINT);
                  HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                  conn.setRequestMethod("POST");
                  conn.setRequestProperty("Authorization", "Bearer " + creds.getAccessToken().getTokenValue());
                  conn.setRequestProperty("Content-Type", "application/json");
                  conn.setDoOutput(true);
                  try (OutputStream os = conn.getOutputStream()) {
                      os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                  }
                  Log.d(TAG, "FCM: " + conn.getResponseCode());
              } catch (Exception ex) { Log.w(TAG, "FCM failed", ex); }
          });
      }
  }