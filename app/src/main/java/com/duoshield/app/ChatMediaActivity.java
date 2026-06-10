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
  import java.util.List;
  import java.util.Map;
  import java.util.UUID;
  import java.util.concurrent.ExecutorService;
  import java.util.concurrent.Executors;

  import javax.crypto.SecretKey;

  public class ChatMediaActivity extends AppCompatActivity {

      private static final String TAG          = "ChatMediaActivity";
      private static final String FCM_ENDPOINT =
              "https://fcm.googleapis.com/v1/projects/duoshield-8caf1/messages:send";
      private static final String FCM_SCOPE    =
              "https://www.googleapis.com/auth/firebase.messaging";

      // F1: Typing indicator debounce
      private final Handler typingHandler = new Handler(Looper.getMainLooper());
      private boolean isTyping = false;

      // F3: Reply state
      private String pendingReplyId      = null;
      private String pendingReplyPreview = null;

      private FirebaseFirestore db;
      private StorageReference  storageRef;
      private EditText          messageInput;
      private Button            sendButton, uploadButton;
      private ProgressBar       uploadProgress;
      private RecyclerView      recyclerView;
      private TextView          typingIndicator;
      private View              replyPreviewBar;
      private TextView          replyPreviewBarText;
      private ImageView         cancelReplyBtn;

      private List<Message>     messages;
      private MessageAdapter    adapter;

      private String conversationId;
      private String myUid;
      private String partnerUid;

      private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

      // Media pickers
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
          Toolbar toolbar = findViewById(R.id.chatToolbar);
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

          messageInput        = findViewById(R.id.messageInput);
          sendButton          = findViewById(R.id.sendButton);
          uploadButton        = findViewById(R.id.uploadButton);
          uploadProgress      = findViewById(R.id.uploadProgress);
          recyclerView        = findViewById(R.id.messageRecycler);
          typingIndicator     = findViewById(R.id.typingIndicator);
          replyPreviewBar     = findViewById(R.id.replyPreviewBar);
          replyPreviewBarText = findViewById(R.id.replyPreviewBarText);
          cancelReplyBtn      = findViewById(R.id.cancelReplyBtn);

          messages = new ArrayList<>();
          adapter  = new MessageAdapter(messages, myUid, null,
              (msg, anchor) -> showMessageActionDialog(msg));  // F3 & F5 long press
          recyclerView.setAdapter(adapter);
          recyclerView.setLayoutManager(new LinearLayoutManager(this));

          db         = FirebaseFirestore.getInstance();
          storageRef = FirebaseStorage.getInstance().getReference();

          applyWallpaper();
          listenForMessages();
          listenForPartnerSeen();
          listenForTyping();      // F1

          sendButton.setOnClickListener(v -> {
              String text = messageInput.getText().toString().trim();
              if (!text.isEmpty()) { sendMessage(text); messageInput.setText(""); }
          });
          uploadButton.setOnClickListener(v -> showMediaTypePopup());

          // F1: typing detection
          messageInput.addTextChangedListener(new TextWatcher() {
              @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
              @Override public void onTextChanged(CharSequence s, int st, int b, int c) { onUserTyping(); }
              @Override public void afterTextChanged(Editable s) {}
          });

          // F3: cancel reply
          cancelReplyBtn.setOnClickListener(v -> clearReplyMode());
      }

      @Override
      protected void onResume() {
          super.onResume();
          markAsSeen();
          clearBadge();
          applyWallpaper();
      }

      // ═══════════════════════════════════════════════════════════════
      // F1: TYPING INDICATOR
      // ═══════════════════════════════════════════════════════════════

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
          if (partnerUid == null || conversationId == null) return;
          db.collection("conversations").document(conversationId)
            .addSnapshotListener((snap, e) -> {
                if (snap == null) return;
                Object val = snap.get("typing_" + partnerUid);
                boolean partnerTyping = Boolean.TRUE.equals(val);
                if (typingIndicator != null)
                    typingIndicator.setVisibility(partnerTyping ? View.VISIBLE : View.GONE);
            });
      }

      // ═══════════════════════════════════════════════════════════════
      // F3: REPLY MODE — long press → show reply bar → send with context
      // ═══════════════════════════════════════════════════════════════

      private void showMessageActionDialog(Message msg) {
          String[] options = {"↩ Reply", "😀 React", "🗑 Delete (local)"};
          new AlertDialog.Builder(this)
              .setItems(options, (d, which) -> {
                  if (which == 0) enterReplyMode(msg);
                  else if (which == 1) showReactionPicker(msg);
                  else deleteMessageLocally(msg);
              }).show();
      }

      private void enterReplyMode(Message msg) {
          pendingReplyId      = msg.getId();
          pendingReplyPreview = (msg.getText() != null && !msg.getText().isEmpty())
              ? msg.getText() : "[media]";
          replyPreviewBarText.setText("Replying to: " + pendingReplyPreview);
          replyPreviewBar.setVisibility(View.VISIBLE);
          messageInput.requestFocus();
      }

      private void clearReplyMode() {
          pendingReplyId      = null;
          pendingReplyPreview = null;
          replyPreviewBar.setVisibility(View.GONE);
      }

      // ═══════════════════════════════════════════════════════════════
      // F4: DISAPPEARING MESSAGES — apply expiresAt when sending
      // ═══════════════════════════════════════════════════════════════

      private long getDisappearMs() {
          return getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
              .getLong("disappear_ms", 0);
      }

      private boolean isExpired(Message m) {
          return m.getExpiresAt() > 0 && System.currentTimeMillis() > m.getExpiresAt();
      }

      // ═══════════════════════════════════════════════════════════════
      // F5: REACTION PICKER
      // ═══════════════════════════════════════════════════════════════

      private void showReactionPicker(Message msg) {
          String[] emojis = {"👍", "❤️", "😂", "😮", "😢", "🙏"};
          new AlertDialog.Builder(this)
              .setTitle("React")
              .setItems(emojis, (d, which) -> sendReaction(msg, emojis[which]))
              .show();
      }

      private void sendReaction(Message msg, String emoji) {
          // Update in Firestore
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msg.getId())
            .update("reaction", emoji);
          // Update local list
          msg.setReaction(emoji);
          int idx = messages.indexOf(msg);
          if (idx >= 0) adapter.notifyItemChanged(idx);
      }

      private void deleteMessageLocally(Message msg) {
          int idx = messages.indexOf(msg);
          if (idx >= 0) {
              messages.remove(idx);
              adapter.notifyItemRemoved(idx);
          }
      }

      // ═══════════════════════════════════════════════════════════════
      // EXISTING FEATURES (unchanged from previous batch)
      // ═══════════════════════════════════════════════════════════════

      private void showMediaTypePopup() {
          new AlertDialog.Builder(this)
              .setTitle("Share")
              .setItems(new String[]{"Image", "Video", "Profile Card"}, (d, w) -> {
                  if      (w == 0) pickImageLauncher.launch("image/*");
                  else if (w == 1) pickVideoLauncher.launch("video/*");
                  else             sendContactCard();
              }).show();
      }

      private void uploadMedia(Uri fileUri, String mediaType) {
          String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
          StorageReference ref = storageRef.child("media").child(conversationId).child(UUID.randomUUID() + ext);
          uploadProgress.setVisibility(View.VISIBLE);
          uploadProgress.setProgress(0);
          uploadButton.setEnabled(false);
          ref.putFile(fileUri)
              .addOnProgressListener(s -> uploadProgress.setProgress((int) (100.0 * s.getBytesTransferred() / s.getTotalByteCount())))
              .addOnFailureListener(e -> {
                  Toast.makeText(this, "Upload failed", Toast.LENGTH_SHORT).show();
                  uploadProgress.setVisibility(View.GONE); uploadButton.setEnabled(true);
              })
              .addOnSuccessListener(s -> ref.getDownloadUrl()
                  .addOnSuccessListener(uri -> {
                      uploadProgress.setVisibility(View.GONE); uploadButton.setEnabled(true);
                      sendMediaMessage(uri.toString(), mediaType);
                  })
                  .addOnFailureListener(e -> { uploadProgress.setVisibility(View.GONE); uploadButton.setEnabled(true); })
              );
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
          doc.put("expiresAt", exp);
          doc.put("timestamp", FieldValue.serverTimestamp());
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msgId).set(doc)
            .addOnSuccessListener(v -> {
                Message m = new Message(msgId, conversationId, myUid, "", now, false, mediaUrl, mediaType);
                m.setExpiresAt(exp);
                saveToRoom(m);
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
                saveToRoom(new Message(msgId, conversationId, myUid, cardText, now, false, null, "contact_card"));
                notifyPartner("DuoShield", "Shared a profile card");
            });
      }

      private void markAsSeen() {
          if (conversationId == null || myUid == null) return;
          db.collection("conversations").document(conversationId)
            .update("lastSeen_" + myUid, FieldValue.serverTimestamp());
      }

      private void listenForPartnerSeen() {
          if (partnerUid == null || conversationId == null) return;
          db.collection("conversations").document(conversationId)
            .addSnapshotListener((snap, e) -> {
                if (snap == null || !snap.exists()) return;
                Object ts = snap.get("lastSeen_" + partnerUid);
                if (ts == null) return;
                long seenMs = (ts instanceof com.google.firebase.Timestamp)
                    ? ((com.google.firebase.Timestamp) ts).toDate().getTime() : 0;
                boolean changed = false;
                for (Message m : messages) {
                    if (myUid.equals(m.getSender()) && !m.isSeen() && m.getTimestamp() <= seenMs) {
                        m.setSeen(true); changed = true;
                    }
                }
                if (changed) adapter.notifyDataSetChanged();
            });
      }

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
                  if (w == opts.length - 1) { pickWallpaperLauncher.launch("image/*"); }
                  else if (w == 0) { ed.putString("wallpaper_type", "none").apply(); applyWallpaper(); }
                  else { ed.putString("wallpaper_type", "color").putInt("wallpaper_color", colors[w]).apply(); applyWallpaper(); }
              }).show();
      }

      private void clearBadge() {
          NotificationManagerCompat.from(this).cancelAll();
          getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit().putInt("badge_count", 0).apply();
      }

      @Override public boolean onCreateOptionsMenu(Menu menu) {
          getMenuInflater().inflate(R.menu.chat_menu, menu); return true;
      }

      @Override public boolean onOptionsItemSelected(MenuItem item) {
          int id = item.getItemId();
          if (id == R.id.action_settings) { startActivity(new Intent(this, SettingsActivity.class)); return true; }
          if (id == R.id.action_wallpaper) { showWallpaperDialog(); return true; }
          return super.onOptionsItemSelected(item);
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

          // F3: reply context
          String replyId      = pendingReplyId;
          String replyPreview = pendingReplyPreview;
          clearReplyMode();

          Map<String, Object> doc = new HashMap<>();
          doc.put("id", msgId); doc.put("conversationId", conversationId);
          doc.put("sender", myUid); doc.put("text", ciphertext);
          doc.put("isEncrypted", true); doc.put("type", "text");
          doc.put("expiresAt", exp);
          if (replyId != null) { doc.put("replyToId", replyId); doc.put("replyPreview", replyPreview); }
          doc.put("timestamp", FieldValue.serverTimestamp());

          final String fc = ciphertext;
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msgId).set(doc)
            .addOnSuccessListener(v -> {
                Message m = new Message(msgId, conversationId, myUid, fc, now, true);
                m.setExpiresAt(exp);
                if (replyId != null) { m.setReplyToId(replyId); m.setReplyPreview(replyPreview); }
                saveToRoom(m);
                notifyPartner("DuoShield", "New message");
            })
            .addOnFailureListener(e -> Toast.makeText(this, "Failed to send.", Toast.LENGTH_SHORT).show());
      }

      private void listenForMessages() {
          db.collection("conversations").document(conversationId)
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
                            Message m = new Message(id, convo, from, text, ts, false, mUrl, mType);
                            if (rpId  != null) m.setReplyToId(rpId);
                            if (rpPrv != null) m.setReplyPreview(rpPrv);
                            if (expAt != null) m.setExpiresAt(expAt);
                            // F4: skip expired messages
                            if (isExpired(m)) continue;
                            messages.add(m);
                            adapter.notifyItemInserted(messages.size() - 1);
                            recyclerView.scrollToPosition(messages.size() - 1);
                            saveToRoom(m);
                        }
                    } else if (dc.getType() == DocumentChange.Type.MODIFIED) {
                        // F5: reaction update
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
                  GoogleCredentials creds = GoogleCredentials.fromStream(sa).createScoped(Arrays.asList(FCM_SCOPE));
                  creds.refreshIfExpired();
                  URL url = new URL(FCM_ENDPOINT);
                  HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                  conn.setRequestMethod("POST");
                  conn.setRequestProperty("Authorization", "Bearer " + creds.getAccessToken().getTokenValue());
                  conn.setRequestProperty("Content-Type", "application/json");
                  conn.setDoOutput(true);
                  try (OutputStream os = conn.getOutputStream()) { os.write(payload.toString().getBytes(StandardCharsets.UTF_8)); }
                  Log.d(TAG, "FCM: " + conn.getResponseCode());
              } catch (Exception ex) { Log.w(TAG, "FCM failed", ex); }
          });
      }
  }