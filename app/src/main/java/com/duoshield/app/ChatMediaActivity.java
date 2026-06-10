package com.duoshield.app;

  import android.app.AlertDialog;
  import android.content.Intent;
  import android.content.SharedPreferences;
  import android.graphics.Color;
  import android.net.Uri;
  import android.os.Bundle;
  import android.util.Log;
  import android.view.Menu;
  import android.view.MenuItem;
  import android.view.View;
  import android.widget.Button;
  import android.widget.EditText;
  import android.widget.ProgressBar;
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
  import com.google.firebase.storage.UploadTask;

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

      private FirebaseFirestore db;
      private StorageReference  storageRef;
      private EditText          messageInput;
      private Button            sendButton, uploadButton;
      private ProgressBar       uploadProgress;
      private RecyclerView      recyclerView;
      private List<Message>     messages;
      private MessageAdapter    adapter;

      private String conversationId;
      private String myUid;
      private String partnerUid;

      private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

      // F1: image picker
      private final ActivityResultLauncher<String> pickImageLauncher =
          registerForActivityResult(new ActivityResultContracts.GetContent(),
              uri -> { if (uri != null) uploadMedia(uri, "image"); });

      // F1: video picker
      private final ActivityResultLauncher<String> pickVideoLauncher =
          registerForActivityResult(new ActivityResultContracts.GetContent(),
              uri -> { if (uri != null) uploadMedia(uri, "video"); });

      // F4: wallpaper image picker
      private final ActivityResultLauncher<String> pickWallpaperLauncher =
          registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
              if (uri != null) {
                  getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit()
                      .putString("wallpaper_type", "image")
                      .putString("wallpaper_uri", uri.toString())
                      .apply();
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

          messageInput   = findViewById(R.id.messageInput);
          sendButton     = findViewById(R.id.sendButton);
          uploadButton   = findViewById(R.id.uploadButton);
          uploadProgress = findViewById(R.id.uploadProgress);
          recyclerView   = findViewById(R.id.messageRecycler);

          messages = new ArrayList<>();
          adapter  = new MessageAdapter(messages, myUid, null, null);
          recyclerView.setAdapter(adapter);
          recyclerView.setLayoutManager(new LinearLayoutManager(this));

          db         = FirebaseFirestore.getInstance();
          storageRef = FirebaseStorage.getInstance().getReference();

          applyWallpaper();
          listenForMessages();
          listenForPartnerSeen();

          sendButton.setOnClickListener(v -> {
              String text = messageInput.getText().toString().trim();
              if (!text.isEmpty()) { sendMessage(text); messageInput.setText(""); }
          });
          uploadButton.setOnClickListener(v -> showMediaTypePopup());
      }

      @Override
      protected void onResume() {
          super.onResume();
          markAsSeen();   // F2
          clearBadge();   // F5
          applyWallpaper(); // F4
      }

      // ── F1: VIDEO SHARING ────────────────────────────────────────────────────

      private void showMediaTypePopup() {
          new AlertDialog.Builder(this)
              .setTitle("Share")
              .setItems(new String[]{"Image", "Video", "Profile Card"}, (d, which) -> {
                  if      (which == 0) pickImageLauncher.launch("image/*");
                  else if (which == 1) pickVideoLauncher.launch("video/*");
                  else                 sendContactCard();
              }).show();
      }

      private void uploadMedia(Uri fileUri, String mediaType) {
          String ext = "video".equals(mediaType) ? ".mp4" : ".jpg";
          StorageReference fileRef = storageRef
              .child("media").child(conversationId).child(UUID.randomUUID() + ext);

          uploadProgress.setVisibility(View.VISIBLE);
          uploadProgress.setProgress(0);
          uploadButton.setEnabled(false);

          fileRef.putFile(fileUri)
              .addOnProgressListener(snap -> {
                  double pct = (100.0 * snap.getBytesTransferred()) / snap.getTotalByteCount();
                  uploadProgress.setProgress((int) pct);
              })
              .addOnFailureListener(e -> {
                  Log.e(TAG, "Upload failed", e);
                  Toast.makeText(this, "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                  uploadProgress.setVisibility(View.GONE);
                  uploadButton.setEnabled(true);
              })
              .addOnSuccessListener(snap ->
                  fileRef.getDownloadUrl().addOnSuccessListener(uri -> {
                      uploadProgress.setVisibility(View.GONE);
                      uploadButton.setEnabled(true);
                      sendMediaMessage(uri.toString(), mediaType);
                  }).addOnFailureListener(e -> {
                      uploadProgress.setVisibility(View.GONE);
                      uploadButton.setEnabled(true);
                  })
              );
      }

      private void sendMediaMessage(String mediaUrl, String mediaType) {
          String msgId = UUID.randomUUID().toString();
          long   now   = System.currentTimeMillis();
          Map<String, Object> doc = new HashMap<>();
          doc.put("id", msgId); doc.put("conversationId", conversationId);
          doc.put("sender", myUid); doc.put("text", "");
          doc.put("mediaUrl", mediaUrl); doc.put("mediaType", mediaType);
          doc.put("isEncrypted", false); doc.put("type", mediaType);
          doc.put("timestamp", FieldValue.serverTimestamp());
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msgId).set(doc)
            .addOnSuccessListener(v -> {
                saveToRoom(new Message(msgId, conversationId, myUid, "", now, false, mediaUrl, mediaType));
                notifyPartner("DuoShield", "video".equals(mediaType) ? "Sent a video" : "Sent an image");
            });
      }

      // ── F2: BLUE TICKS ───────────────────────────────────────────────────────

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
                long seenMs = 0;
                if (ts instanceof com.google.firebase.Timestamp)
                    seenMs = ((com.google.firebase.Timestamp) ts).toDate().getTime();
                final long cut = seenMs;
                boolean changed = false;
                for (Message m : messages) {
                    if (myUid.equals(m.getSender()) && !m.isSeen() && m.getTimestamp() <= cut) {
                        m.setSeen(true); changed = true;
                    }
                }
                if (changed) adapter.notifyDataSetChanged();
            });
      }

      // ── F3: CONTACT CARD ─────────────────────────────────────────────────────

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

      // ── F4: WALLPAPER ────────────────────────────────────────────────────────

      private void applyWallpaper() {
          if (recyclerView == null) return;
          SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
          String type = prefs.getString("wallpaper_type", "none");
          switch (type) {
              case "color":
                  recyclerView.setBackgroundColor(prefs.getInt("wallpaper_color", Color.WHITE));
                  break;
              case "image":
                  String uriStr = prefs.getString("wallpaper_uri", null);
                  if (uriStr != null) {
                      Glide.with(this).load(Uri.parse(uriStr)).centerCrop()
                          .into(new com.bumptech.glide.request.target.CustomTarget<android.graphics.drawable.Drawable>() {
                              @Override public void onResourceReady(android.graphics.drawable.Drawable r,
                                  com.bumptech.glide.request.transition.Transition<? super android.graphics.drawable.Drawable> t) {
                                  recyclerView.setBackground(r);
                              }
                              @Override public void onLoadCleared(android.graphics.drawable.Drawable p) {
                                  recyclerView.setBackground(null);
                              }
                          });
                  }
                  break;
              default:
                  recyclerView.setBackground(null);
          }
      }

      private void showWallpaperDialog() {
          String[] opts   = {"None", "Soft Blue", "Forest Green", "Dark Night", "Blush Pink", "Pick from gallery…"};
          int[]    colors = {0, 0xFFDCEEFB, 0xFFD7EDDC, 0xFF1A1A2E, 0xFFFDE8EC};
          new AlertDialog.Builder(this).setTitle("Chat wallpaper")
              .setItems(opts, (d, which) -> {
                  SharedPreferences.Editor ed = getSharedPreferences("duoshield_prefs", MODE_PRIVATE).edit();
                  if (which == opts.length - 1) {
                      pickWallpaperLauncher.launch("image/*");
                  } else if (which == 0) {
                      ed.putString("wallpaper_type", "none").apply();
                      applyWallpaper();
                  } else {
                      ed.putString("wallpaper_type", "color").putInt("wallpaper_color", colors[which]).apply();
                      applyWallpaper();
                  }
              }).show();
      }

      // ── F5: BADGE ────────────────────────────────────────────────────────────

      private void clearBadge() {
          NotificationManagerCompat.from(this).cancelAll();
          getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
              .edit().putInt("badge_count", 0).apply();
      }

      // ── Menu ─────────────────────────────────────────────────────────────────

      @Override public boolean onCreateOptionsMenu(Menu menu) {
          getMenuInflater().inflate(R.menu.chat_menu, menu); return true;
      }

      @Override public boolean onOptionsItemSelected(MenuItem item) {
          int id = item.getItemId();
          if (id == R.id.action_settings) {
              startActivity(new Intent(this, SettingsActivity.class)); return true;
          }
          if (id == R.id.action_wallpaper) {
              showWallpaperDialog(); return true;
          }
          return super.onOptionsItemSelected(item);
      }

      // ── Core helpers ─────────────────────────────────────────────────────────

      private void sendMessage(String plaintext) {
          String ciphertext;
          try {
              SecretKey key = resolveKey();
              ciphertext = CryptoHelper.encrypt(plaintext, key);
          } catch (Exception e) {
              Log.e(TAG, "Encryption failed", e);
              Toast.makeText(this, "Encryption error — message not sent", Toast.LENGTH_SHORT).show();
              return;
          }
          String msgId = UUID.randomUUID().toString();
          long   now   = System.currentTimeMillis();
          Map<String, Object> doc = new HashMap<>();
          doc.put("id", msgId); doc.put("conversationId", conversationId);
          doc.put("sender", myUid); doc.put("text", ciphertext);
          doc.put("isEncrypted", true); doc.put("type", "text");
          doc.put("timestamp", FieldValue.serverTimestamp());
          final String fc = ciphertext;
          db.collection("conversations").document(conversationId)
            .collection("messages").document(msgId).set(doc)
            .addOnSuccessListener(v -> {
                saveToRoom(new Message(msgId, conversationId, myUid, fc, now, true));
                notifyPartner("DuoShield", "New message");
            })
            .addOnFailureListener(e ->
                Toast.makeText(this, "Failed to send.", Toast.LENGTH_SHORT).show());
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
                        long   ts    = System.currentTimeMillis();
                        if (id != null) {
                            Message m = new Message(id, convo, from, text, ts, false, mUrl, mType);
                            messages.add(m);
                            adapter.notifyItemInserted(messages.size() - 1);
                            recyclerView.scrollToPosition(messages.size() - 1);
                            saveToRoom(m);
                        }
                    }
                }
            });
      }

      private void saveToRoom(Message m) {
          dbExecutor.execute(() -> AppDatabase.getInstance(this).messageDao().insert(m));
      }

      private SecretKey resolveKey() throws Exception {
          SecretKey k = CryptoInitializer.getSharedKey(this);
          if (k == null) k = KeyManager.getKey();
          return k;
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
                  notification.put("title", title);
                  notification.put("body", body);
                  JSONObject msgObj = new JSONObject();
                  msgObj.put("token", token);
                  msgObj.put("notification", notification);
                  JSONObject payload = new JSONObject();
                  payload.put("message", msgObj);

                  InputStream saStream = getAssets().open("service-account.json");
                  GoogleCredentials creds = GoogleCredentials
                      .fromStream(saStream).createScoped(Arrays.asList(FCM_SCOPE));
                  creds.refreshIfExpired();
                  String accessToken = creds.getAccessToken().getTokenValue();

                  URL url = new URL(FCM_ENDPOINT);
                  HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                  conn.setRequestMethod("POST");
                  conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                  conn.setRequestProperty("Content-Type", "application/json");
                  conn.setDoOutput(true);
                  try (OutputStream os = conn.getOutputStream()) {
                      os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
                  }
                  Log.d(TAG, "FCM response: " + conn.getResponseCode());
              } catch (Exception ex) { Log.w(TAG, "FCM send failed", ex); }
          });
      }
  }