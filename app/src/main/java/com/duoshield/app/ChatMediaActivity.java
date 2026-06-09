package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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

    private static final String TAG = "ChatMediaActivity";

    // ── DEVELOPER NOTE ────────────────────────────────────────────────────────
    // Before building, place your Firebase service account key JSON file at:
    //   app/src/main/assets/service-account.json
    //
    // To obtain the file:
    //   Firebase Console → Project Settings → Service Accounts → Generate New Private Key
    // The key must have the "firebase.messaging" OAuth2 scope.
    //
    // IMPORTANT: Never commit service-account.json to version control.
    // Add it to .gitignore immediately.
    // ──────────────────────────────────────────────────────────────────────────

    private static final String FCM_ENDPOINT =
            "https://fcm.googleapis.com/v1/projects/duoshield-8caf1/messages:send";
    private static final String FCM_SCOPE =
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

    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    // ── File picker ───────────────────────────────────────────────────────────
    private final ActivityResultLauncher<String> pickImageLauncher =
        registerForActivityResult(new ActivityResultContracts.GetContent(), uri -> {
            if (uri != null) uploadMedia(uri);
        });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ── Biometric gate — must run BEFORE any UI is shown ─────────────────
        SharedPreferences prefsCheck = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        boolean biometricEnabled = prefsCheck.getBoolean("biometric_enabled", false);

        if (biometricEnabled) {
            BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
                @Override
                public void onSuccess() {
                    setupChat();
                }

                @Override
                public void onFailure() {
                    finish(); // send user back — no chat content ever shown
                }
            });
        } else {
            setupChat();
        }
    }

    /** All original onCreate logic lives here, called only after auth succeeds. */
    private void setupChat() {
        setContentView(R.layout.activity_chat_media);

        // Toolbar — hosts Settings menu item
        Toolbar toolbar = findViewById(R.id.chatToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("DuoShield");
        }

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        conversationId = prefs.getString("conversation_id", null);
        myUid          = prefs.getString("my_uid", FirebaseAuth.getInstance().getUid());

        if (conversationId == null) {
            Toast.makeText(this, "No active conversation. Please pair first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        messageInput   = findViewById(R.id.messageInput);
        sendButton     = findViewById(R.id.sendButton);
        uploadButton   = findViewById(R.id.uploadButton);
        uploadProgress = findViewById(R.id.uploadProgress);
        recyclerView   = findViewById(R.id.messageRecycler);

        messages = new ArrayList<>();
        adapter  = new MessageAdapter(messages);
        recyclerView.setAdapter(adapter);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        db         = FirebaseFirestore.getInstance();
        storageRef = FirebaseStorage.getInstance().getReference();

        listenForMessages();

        sendButton.setOnClickListener(v -> {
            String text = messageInput.getText().toString().trim();
            if (!text.isEmpty()) {
                sendMessage(text);
                messageInput.setText("");
            }
        });

        uploadButton.setEnabled(true);
        uploadButton.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
    }

    // ── Options menu (Settings icon) ──────────────────────────────────────────

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.chat_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ── Send encrypted text message ───────────────────────────────────────────

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

        String messageId      = UUID.randomUUID().toString();
        long   localTimestamp = System.currentTimeMillis();

        Map<String, Object> docData = new HashMap<>();
        docData.put("id",             messageId);
        docData.put("conversationId", conversationId);
        docData.put("sender",         myUid);
        docData.put("text",           ciphertext);
        docData.put("isEncrypted",    true);
        docData.put("type",           "text");
        docData.put("timestamp",      FieldValue.serverTimestamp());

        final String finalCiphertext = ciphertext;
        db.collection("conversations").document(conversationId)
          .collection("messages").document(messageId)
          .set(docData)
          .addOnSuccessListener(v -> {
              Log.d(TAG, "Text message sent");
              saveToRoom(new Message(messageId, conversationId, myUid,
                                     finalCiphertext, localTimestamp, true));
              notifyPartner("DuoShield", "New message");
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error sending message", e);
              Toast.makeText(this, "Failed to send message.", Toast.LENGTH_SHORT).show();
          });
    }

    // ── Upload image to Firebase Storage ──────────────────────────────────────

    private void uploadMedia(Uri fileUri) {
        StorageReference fileRef = storageRef
            .child("media").child(conversationId).child(UUID.randomUUID().toString());

        uploadProgress.setVisibility(View.VISIBLE);
        uploadProgress.setProgress(0);
        uploadButton.setEnabled(false);

        UploadTask task = fileRef.putFile(fileUri);
        task.addOnProgressListener(snap -> {
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
                 fileRef.getDownloadUrl()
                        .addOnSuccessListener(uri -> {
                            uploadProgress.setVisibility(View.GONE);
                            uploadButton.setEnabled(true);
                            sendMediaMessage(uri.toString());
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Could not get download URL", e);
                            Toast.makeText(this, "Upload OK but URL unavailable.", Toast.LENGTH_SHORT).show();
                            uploadProgress.setVisibility(View.GONE);
                            uploadButton.setEnabled(true);
                        })
             );
    }

    // ── Write media message to Firestore + Room ───────────────────────────────

    private void sendMediaMessage(String mediaUrl) {
        String messageId      = UUID.randomUUID().toString();
        long   localTimestamp = System.currentTimeMillis();

        Map<String, Object> docData = new HashMap<>();
        docData.put("id",             messageId);
        docData.put("conversationId", conversationId);
        docData.put("sender",         myUid);
        docData.put("text",           "");
        docData.put("mediaUrl",       mediaUrl);
        docData.put("isEncrypted",    false);
        docData.put("type",           "image");
        docData.put("timestamp",      FieldValue.serverTimestamp());

        db.collection("conversations").document(conversationId)
          .collection("messages").document(messageId)
          .set(docData)
          .addOnSuccessListener(v -> {
              Log.d(TAG, "Media message sent");
              saveToRoom(new Message(messageId, conversationId, myUid,
                                     "", localTimestamp, false, mediaUrl));
              notifyPartner("DuoShield", "Sent an image");
          })
          .addOnFailureListener(e -> {
              Log.e(TAG, "Error sending media message", e);
              Toast.makeText(this, "Failed to send image.", Toast.LENGTH_SHORT).show();
          });
    }

    // ── FCM push to partner ───────────────────────────────────────────────────

    /** Looks up the partner's FCM token from Firestore, then fires the push. */
    private void notifyPartner(String title, String body) {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String partnerUid = prefs.getString("partner_uid", null);
        if (partnerUid == null) return;

        db.collection("users").document(partnerUid).get()
                .addOnSuccessListener(doc -> {
                    String partnerToken = doc.getString("fcmToken");
                    if (partnerToken != null && !partnerToken.isEmpty()) {
                        sendFcmNotification(partnerToken, title, body);
                    }
                })
                .addOnFailureListener(e -> Log.w(TAG, "Could not fetch partner FCM token", e));
    }

    /**
     * POSTs an FCM HTTP v1 message to the partner device on a background thread.
     *
     * DEVELOPER NOTE: Place your Firebase service account key at
     *   app/src/main/assets/service-account.json
     * before building. Never commit this file to version control.
     */
    private void sendFcmNotification(String token, String title, String body) {
        dbExecutor.execute(() -> {
            try {
                // 1. Build the JSON payload
                JSONObject notification = new JSONObject();
                notification.put("title", title);
                notification.put("body", body);

                JSONObject message = new JSONObject();
                message.put("token", token);
                message.put("notification", notification);

                JSONObject payload = new JSONObject();
                payload.put("message", message);

                byte[] payloadBytes = payload.toString().getBytes(StandardCharsets.UTF_8);

                // 2. Obtain a short-lived OAuth2 Bearer token from the service account
                String accessToken = getAccessToken();
                if (accessToken == null) {
                    Log.e(TAG, "FCM: could not obtain access token — skipping push");
                    return;
                }

                // 3. POST to FCM HTTP v1
                URL url = new URL(FCM_ENDPOINT);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                conn.setRequestProperty("Authorization", "Bearer " + accessToken);
                conn.setDoOutput(true);
                conn.setConnectTimeout(10_000);
                conn.setReadTimeout(10_000);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payloadBytes);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    Log.d(TAG, "FCM push sent successfully");
                } else {
                    Log.w(TAG, "FCM push failed, HTTP " + responseCode);
                }
                conn.disconnect();

            } catch (Exception e) {
                Log.e(TAG, "FCM push error", e);
            }
        });
    }

    /**
     * Exchanges the bundled service account JSON for a short-lived OAuth2 access token
     * scoped to firebase.messaging, using the Google Auth Library.
     *
     * Returns null on failure so the caller can bail out gracefully.
     * DEVELOPER NOTE: Place your Firebase service account key at
     *   app/src/main/assets/service-account.json before building.
     */
    private String getAccessToken() {
        InputStream serviceAccountStream = null;
        try {
            serviceAccountStream = getAssets().open("service-account.json");
        } catch (java.io.IOException ioEx) {
            Log.w(TAG, "service-account.json not found in assets — FCM push disabled. " +
                    "See app/src/main/assets/README.txt for setup instructions.");
            return null;
        }
        try {
            GoogleCredentials credentials = GoogleCredentials
                    .fromStream(serviceAccountStream)
                    .createScoped(Arrays.asList(FCM_SCOPE));
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (Exception e) {
            Log.e(TAG, "getAccessToken failed", e);
            return null;
        } finally {
            try { serviceAccountStream.close(); } catch (Exception ignored) {}
        }
    }

    // ── Listen for incoming messages ──────────────────────────────────────────

    private void listenForMessages() {
        db.collection("conversations").document(conversationId)
          .collection("messages").orderBy("timestamp")
          .addSnapshotListener((snapshots, e) -> {
              if (e != null) { Log.w(TAG, "Listen failed.", e); return; }
              if (snapshots == null) return;

              for (DocumentChange dc : snapshots.getDocumentChanges()) {
                  if (dc.getType() != DocumentChange.Type.ADDED) continue;

                  String  id          = dc.getDocument().getString("id");
                  String  sender      = dc.getDocument().getString("sender");
                  String  rawText     = dc.getDocument().getString("text");
                  String  mediaUrl    = dc.getDocument().getString("mediaUrl");
                  Long    ts          = dc.getDocument().getLong("timestamp");
                  Boolean encrypted   = dc.getDocument().getBoolean("isEncrypted");

                  long    timestamp   = (ts != null)           ? ts    : System.currentTimeMillis();
                  boolean isEncrypted = Boolean.TRUE.equals(encrypted);
                  String  safeId      = (id != null)           ? id    : UUID.randomUUID().toString();

                  String displayText;
                  if (isEncrypted && rawText != null && !rawText.isEmpty()) {
                      try {
                          displayText = CryptoHelper.decrypt(rawText, resolveKey());
                      } catch (Exception ex) {
                          Log.w(TAG, "Decryption failed — partner key", ex);
                          displayText = "[Encrypted message]";
                      }
                  } else {
                      displayText = (rawText != null) ? rawText : "";
                  }

                  Message uiMsg = new Message(safeId, conversationId, sender,
                                              displayText, timestamp, isEncrypted, mediaUrl);
                  messages.add(uiMsg);
                  adapter.notifyItemInserted(messages.size() - 1);
                  recyclerView.scrollToPosition(messages.size() - 1);

                  saveToRoom(new Message(safeId, conversationId, sender,
                                         (rawText != null) ? rawText : "",
                                         timestamp, isEncrypted, mediaUrl));
              }
          });
    }

    // ── Key resolution: ECDH shared key preferred, AES fallback ──────────────

    /**
     * Returns the best available encryption key:
     *   1. ECDH-derived shared AES-256 key (true E2E — both devices derive the same key)
     *   2. Fallback: AndroidKeyStore AES-256 key (symmetric, same device only)
     *
     * If the shared key is not yet stored (partner key wasn't available at pairing time),
     * this method attempts an on-demand re-derivation by fetching the partner's EC
     * public key from Firestore. The fetch is async so the first call may still fall back;
     * subsequent calls after the Firestore result returns will use the shared key.
     */
    private SecretKey resolveKey() throws Exception {
        // Prefer ECDH shared key
        SecretKey shared = CryptoInitializer.getSharedKey(this);
        if (shared != null) return shared;

        // Try to derive on demand (partner key may have arrived since pairing)
        attemptKeyRederivation();

        // Fallback to AndroidKeyStore AES key
        SecretKey aes = KeyManager.getKey();
        if (aes == null) {
            KeyManager.generateKey();
            aes = KeyManager.getKey();
        }
        return aes;
    }

    /**
     * Async: fetch partner's EC public key from Firestore and derive the shared key
     * if it hasn't been stored yet. Safe to call repeatedly — exits early if key exists.
     */
    private void attemptKeyRederivation() {
        android.content.SharedPreferences prefs =
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        if (prefs.getString(CryptoInitializer.KEY_SHARED_AES, null) != null) return;

        String partnerUid = prefs.getString("partner_uid", null);
        if (partnerUid == null) return;

        db.collection("users").document(partnerUid).get()
          .addOnSuccessListener(doc -> {
              String partnerPubB64 = doc.getString("ecPublicKey");
              if (partnerPubB64 == null || partnerPubB64.isEmpty()) return;
              try {
                  java.security.PrivateKey myPriv =
                          CryptoInitializer.getMyPrivateKey(getApplicationContext());
                  if (myPriv == null) return;
                  SecretKey derived = com.duoshield.app.crypto.ECDHHelper
                          .deriveSharedKey(myPriv, partnerPubB64);
                  String b64 = android.util.Base64.encodeToString(
                          derived.getEncoded(), android.util.Base64.NO_WRAP);
                  prefs.edit().putString(CryptoInitializer.KEY_SHARED_AES, b64).apply();
              } catch (Exception ignored) {}
          });
    }

    // ── Room helper ───────────────────────────────────────────────────────────

    private void saveToRoom(Message message) {
        dbExecutor.execute(() ->
            AppDatabase.getInstance(getApplicationContext()).messageDao().insert(message)
        );
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!dbExecutor.isShutdown()) dbExecutor.shutdown();
    }
}
