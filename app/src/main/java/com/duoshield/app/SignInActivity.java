package com.duoshield.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.crypto.RecoveryHelper;
import com.duoshield.app.ui.PairingActivity;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.SetOptions;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class SignInActivity extends AppCompatActivity {

    private FirebaseAuth            mAuth;
    private FirebaseFirestore       db;

    private TextInputLayout         tilEmail, tilPassword;
    private TextInputEditText       etEmail, etPassword;
    private MaterialButton          btnAction;
    private TextView                tvToggleMode, tvForgotPassword, tvError;
    private LinearProgressIndicator progressSignIn;

    private boolean isSignUpMode = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_sign_in);

        mAuth = FirebaseAuth.getInstance();
        db    = FirebaseFirestore.getInstance();

        tilEmail         = findViewById(R.id.tilEmail);
        tilPassword      = findViewById(R.id.tilPassword);
        etEmail          = findViewById(R.id.etEmail);
        etPassword       = findViewById(R.id.etPassword);
        btnAction        = findViewById(R.id.btnSignIn);
        tvToggleMode     = findViewById(R.id.tvSignUp);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        progressSignIn   = findViewById(R.id.progressSignIn);
        tvError          = findViewById(R.id.tvError);

        btnAction.setOnClickListener(v -> { if (isSignUpMode) register(); else signIn(); });
        tvToggleMode.setOnClickListener(v -> toggleMode());
        tvForgotPassword.setOnClickListener(v -> showForgotPassphraseDialog());
    }

    @Override
    protected void onStart() {
        super.onStart();
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) route(user.getUid());
    }

    // ── Mode toggle ───────────────────────────────────────────────────────────

    private void toggleMode() {
        isSignUpMode = !isSignUpMode;
        clearErrors();
        if (isSignUpMode) {
            btnAction.setText(R.string.sign_up);
            tvToggleMode.setText(R.string.have_account);
            tvForgotPassword.setVisibility(View.GONE);
        } else {
            btnAction.setText(R.string.sign_in);
            tvToggleMode.setText(R.string.no_account);
            tvForgotPassword.setVisibility(View.VISIBLE);
        }
    }

    // ── Sign in ───────────────────────────────────────────────────────────────

    private void signIn() {
        String email = getEmail();
        String pass  = getPassphrase();
        if (!validateSignIn(email, pass)) return;
        setLoading(true);
        mAuth.signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    setLoading(false);
                    FirebaseUser u = r.getUser();
                    if (u == null) {
                        showError(getString(R.string.error_generic));
                        return;
                    }
                    route(u.getUid());
                })
                .addOnFailureListener(e -> { setLoading(false); showError(friendlyError(e.getMessage())); });
    }

    // ── Register ──────────────────────────────────────────────────────────────

    private void register() {
        String email = getEmail();
        String pass  = getPassphrase();
        if (!validateSignUp(email, pass)) return;
        setLoading(true);
        mAuth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    setLoading(false);
                    FirebaseUser u = r.getUser();
                    if (u == null) {
                        showError(getString(R.string.error_generic));
                        return;
                    }
                    String uid          = u.getUid();
                    String userId       = RecoveryHelper.generateUserId();
                    String recoveryCode = RecoveryHelper.generateRecoveryCode();
                    showRecoveryDialog(email, pass, userId, recoveryCode, uid);
                })
                .addOnFailureListener(e -> { setLoading(false); showError(friendlyError(e.getMessage())); });
    }

    // ── Recovery dialog (shown ONCE at signup) ────────────────────────────────

    private void showRecoveryDialog(String email, String loginPass,
                                     String userId, String recoveryCode, String uid) {
        View v = getLayoutInflater().inflate(R.layout.dialog_show_recovery, null);

        TextView       tvUid      = v.findViewById(R.id.tvUserId);
        TextView       tvCode     = v.findViewById(R.id.tvRecoveryCode);
        MaterialButton btnCopyUid = v.findViewById(R.id.btnCopyUserId);
        MaterialButton btnCopyCod = v.findViewById(R.id.btnCopyCode);
        CheckBox       cbSaved    = v.findViewById(R.id.cbSaved);

        tvUid.setText(userId);
        tvCode.setText(recoveryCode);

        btnCopyUid.setOnClickListener(x -> copyToClipboard("user_id",   userId,
                getString(R.string.user_id_copied)));
        btnCopyCod.setOnClickListener(x -> copyToClipboard("recovery_code", recoveryCode,
                getString(R.string.recovery_code_copied)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_code_title)
                .setView(v)
                .setCancelable(false)
                .setPositiveButton(R.string.recovery_code_continue, null)
                .create();

        dialog.setOnShowListener(d -> {
            MaterialButton cont = (MaterialButton) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            cont.setEnabled(false);
            cbSaved.setOnCheckedChangeListener((btn, checked) -> cont.setEnabled(checked));
            cont.setOnClickListener(x -> {
                dialog.dismiss();
                setLoading(true);
                persistNewAccount(email, loginPass, userId, recoveryCode, uid,
                        () -> { setLoading(false); route(uid); },
                        err -> { setLoading(false); showError(err); });
            });
        });

        dialog.show();
    }

    /**
     * After the user confirms they've saved their credentials:
     *  1. Encrypt {email, passphrase} with recovery code → recovery/{uid}
     *  2. Map userId → uid in identities/{userId}
     *  3. Save userId to users/{uid} and to local SharedPreferences
     */
    private void persistNewAccount(String email, String loginPass, String userId,
                                    String recoveryCode, String uid,
                                    Runnable onDone, Callback onError) {
        new Thread(() -> {
            try {
                String blob = RecoveryHelper.encryptCredentials(email, loginPass, recoveryCode);

                // recovery/{uid}
                Map<String, Object> recoveryDoc = new HashMap<>();
                recoveryDoc.put("enc", blob);

                // identities/{userId} — userId → uid (for reset lookup)
                Map<String, Object> identityDoc = new HashMap<>();
                identityDoc.put("uid", uid);

                // users/{uid} — add userId field
                Map<String, Object> userUpdate = new HashMap<>();
                userUpdate.put("userId", userId);

                // Fan-out writes
                db.collection("recovery").document(uid).set(recoveryDoc);
                db.collection("identities").document(userId).set(identityDoc);
                db.collection("users").document(uid)
                        .set(userUpdate, SetOptions.merge());

                // Persist locally
                getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                        .edit().putString("my_user_id", userId).apply();

                runOnUiThread(onDone);
            } catch (Exception e) {
                runOnUiThread(() -> onError.call(getString(R.string.error_generic)));
            }
        }).start();
    }

    // ── Forgot passphrase dialog ──────────────────────────────────────────────

    private void showForgotPassphraseDialog() {
        View v = getLayoutInflater().inflate(R.layout.dialog_forgot_passphrase, null);

        TextInputEditText etUserId    = v.findViewById(R.id.etResetUserId);
        TextInputEditText etRecovery  = v.findViewById(R.id.etResetRecovery);
        TextInputEditText etNewPass   = v.findViewById(R.id.etNewPassphrase);
        TextInputEditText etConfirm   = v.findViewById(R.id.etConfirmPassphrase);
        TextView          tvErr       = v.findViewById(R.id.tvResetError);

        // Pre-fill User ID from local prefs if available
        String savedUserId = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getString("my_user_id", null);
        if (!TextUtils.isEmpty(savedUserId) && etUserId != null)
            etUserId.setText(savedUserId);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reset_passphrase_title)
                .setView(v)
                .setPositiveButton(R.string.reset_apply, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x -> {
                    String userId   = text(etUserId).toUpperCase();
                    String recovery = text(etRecovery);
                    String newPass  = text(etNewPass);
                    String confirm  = text(etConfirm);

                    tvErr.setVisibility(View.GONE);

                    if (TextUtils.isEmpty(userId)) {
                        setDialogErr(tvErr, getString(R.string.error_user_id_required)); return;
                    }
                    if (!userId.matches("DS-[0-9A-F]{8}")) {
                        setDialogErr(tvErr, getString(R.string.error_user_id_invalid)); return;
                    }
                    if (TextUtils.isEmpty(recovery)) {
                        setDialogErr(tvErr, getString(R.string.error_recovery_required)); return;
                    }
                    if (newPass.length() < 8) {
                        setDialogErr(tvErr, getString(R.string.error_passphrase_length)); return;
                    }
                    if (!newPass.equals(confirm)) {
                        setDialogErr(tvErr, getString(R.string.error_passphrases_no_match)); return;
                    }

                    setDialogBtns(dialog, false);
                    performReset(userId, recovery, newPass, tvErr, dialog);
                }));

        dialog.show();
    }

    /**
     * Passphrase reset — no email, no OTP:
     *  1. identities/{userId} → uid
     *  2. recovery/{uid}     → encrypted blob
     *  3. Decrypt blob with recovery code → {email, oldPassphrase}
     *  4. signInWithEmailAndPassword(email, oldPassphrase)
     *  5. user.updatePassword(newPassphrase)
     *  6. Re-encrypt {email, newPassphrase} → update recovery/{uid}
     */
    private void performReset(String userId, String recoveryCode, String newPass,
                               TextView tvErr, AlertDialog dialog) {
        // Step 1: resolve userId → uid
        db.collection("identities").document(userId).get()
                .addOnSuccessListener(idSnap -> {
                    if (!idSnap.exists() || TextUtils.isEmpty(idSnap.getString("uid"))) {
                        setDialogBtns(dialog, true);
                        setDialogErr(tvErr, getString(R.string.error_user_id_not_found));
                        return;
                    }
                    String uid = idSnap.getString("uid");

                    // Step 2: fetch recovery blob
                    db.collection("recovery").document(uid).get()
                            .addOnSuccessListener(recSnap -> {
                                String blob = recSnap.getString("enc");
                                if (!recSnap.exists() || TextUtils.isEmpty(blob)) {
                                    setDialogBtns(dialog, true);
                                    setDialogErr(tvErr, getString(R.string.error_recovery_corrupt));
                                    return;
                                }

                                // Step 3: decrypt in background thread
                                new Thread(() -> {
                                    try {
                                        String[] creds = RecoveryHelper.decryptCredentials(blob, recoveryCode);
                                        String email      = creds[0];
                                        String oldPass    = creds[1];
                                        runOnUiThread(() -> reauthAndUpdate(
                                                email, oldPass, newPass, uid, recoveryCode,
                                                tvErr, dialog));
                                    } catch (Exception e) {
                                        runOnUiThread(() -> {
                                            setDialogBtns(dialog, true);
                                            setDialogErr(tvErr, getString(R.string.error_recovery_wrong));
                                        });
                                    }
                                }).start();
                            })
                            .addOnFailureListener(e -> {
                                setDialogBtns(dialog, true);
                                setDialogErr(tvErr, friendlyError(e.getMessage()));
                            });
                })
                .addOnFailureListener(e -> {
                    setDialogBtns(dialog, true);
                    setDialogErr(tvErr, friendlyError(e.getMessage()));
                });
    }

    private void reauthAndUpdate(String email, String oldPass, String newPass,
                                  String uid, String recoveryCode,
                                  TextView tvErr, AlertDialog dialog) {
        mAuth.signInWithEmailAndPassword(email, oldPass)
                .addOnSuccessListener(r -> {
                    FirebaseUser user = r.getUser();
                    if (user == null) {
                        setDialogBtns(dialog, true);
                        setDialogErr(tvErr, getString(R.string.error_generic));
                        return;
                    }
                    user.updatePassword(newPass)
                            .addOnSuccessListener(x -> {
                                reEncryptRecovery(email, newPass, uid, recoveryCode);
                                dialog.dismiss();
                                showSuccess(getString(R.string.reset_passphrase_success));
                            })
                            .addOnFailureListener(e -> {
                                setDialogBtns(dialog, true);
                                setDialogErr(tvErr, friendlyError(e.getMessage()));
                            });
                })
                .addOnFailureListener(e -> {
                    setDialogBtns(dialog, true);
                    setDialogErr(tvErr, getString(R.string.error_recovery_wrong));
                });
    }

    private void reEncryptRecovery(String email, String newPass, String uid, String recoveryCode) {
        new Thread(() -> {
            try {
                String newBlob = RecoveryHelper.encryptCredentials(email, newPass, recoveryCode);
                db.collection("recovery").document(uid)
                        .update(Collections.singletonMap("enc", newBlob));
            } catch (Exception ignored) {}
        }).start();
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private void route(String uid) {
        CryptoInitializer.ensureKeyExists(this);
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putString("my_uid", uid).apply();
        FcmTokenHelper.register(this);
        AppLockManager.onAppForegrounded(this);
        restoreStateFromFirestore(uid, prefs);
    }

    /**
     * Restores account state from Firestore after sign-in / re-login.
     * Fetches userId (DS-XXXXXXXX), conversation_id, and partner_uid so
     * the user lands in their chat rather than PairingActivity on re-login.
     */
    @SuppressWarnings("unchecked")
    private void restoreStateFromFirestore(String uid, SharedPreferences prefs) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener(userDoc -> {
                SharedPreferences.Editor ed = prefs.edit();

                // Restore DS-XXXXXXXX user ID
                String savedUserId = prefs.getString("my_user_id", null);
                if (savedUserId == null) {
                    String firestoreUserId = userDoc.getString("userId");
                    if (firestoreUserId != null) ed.putString("my_user_id", firestoreUserId);
                }
                ed.apply();

                // If already marked as paired locally, just navigate
                if (prefs.getBoolean("is_paired", false)
                        && prefs.getString("conversation_id", null) != null) {
                    navigateAfterRestore(prefs);
                    return;
                }

                // Try to restore pairing from Firestore chats
                db.collection("chats")
                    .whereArrayContains("participants", uid)
                    .limit(1)
                    .get()
                    .addOnSuccessListener(chats -> {
                        if (!chats.isEmpty()) {
                            com.google.firebase.firestore.DocumentSnapshot chatDoc =
                                chats.getDocuments().get(0);
                            String chatId = chatDoc.getId();
                            java.util.List<String> participants = null;
                            Object raw = chatDoc.get("participants");
                            if (raw instanceof java.util.List) {
                                try { participants = (java.util.List<String>) raw; }
                                catch (ClassCastException ignored) {}
                            }
                            String partnerUid = null;
                            if (participants != null) {
                                for (String p : participants) {
                                    if (!p.equals(uid)) { partnerUid = p; break; }
                                }
                            }
                            if (partnerUid != null) {
                                final String finalPartner = partnerUid;
                                prefs.edit()
                                    .putBoolean("is_paired", true)
                                    .putString("conversation_id", chatId)
                                    .putString("partner_uid", partnerUid)
                                    .apply();
                                // Attempt to re-derive ECDH shared key in background
                                reDeriveEcdhIfNeeded(uid, finalPartner);
                            }
                        }
                        navigateAfterRestore(prefs);
                    })
                    .addOnFailureListener(e -> navigateAfterRestore(prefs));
            })
            .addOnFailureListener(e -> navigateAfterRestore(prefs));
    }

    /**
     * Re-derives the ECDH shared key if missing, using Firestore-stored public keys.
     * Handles both re-login (same device) and reinstall (new EC key pair).
     */
    private void reDeriveEcdhIfNeeded(String myUid, String partnerUid) {
        android.content.SharedPreferences secure =
            com.duoshield.app.util.SecurePrefs.get(getApplicationContext());
        // Do NOT exit early based on key presence alone — partner may have reinstalled
        // and rotated their EC key pair. Always fetch the current pub key first.

        // Fetch partner's current EC public key from Firestore
        db.collection("users").document(partnerUid).get()
            .addOnSuccessListener(doc -> {
                String partnerPub = doc.getString("ecPublicKey");
                if (partnerPub == null) return;

                // Skip re-derive only when key exists AND partner's pub key hasn't rotated
                String storedPub = secure.getString(
                    com.duoshield.app.crypto.CryptoInitializer.KEY_PARTNER_EC_PUBLIC, null);
                String existingKey = secure.getString(
                    com.duoshield.app.crypto.CryptoInitializer.KEY_SHARED_AES, null);
                if (existingKey != null && partnerPub.equals(storedPub)) return;
                // Crypto work on background thread
                new Thread(() -> {
                    try {
                        java.security.PrivateKey myPriv =
                            com.duoshield.app.crypto.CryptoInitializer
                                .getMyPrivateKey(getApplicationContext());
                        if (myPriv == null) {
                            // Reinstall: generate a new EC key pair and publish it
                            com.duoshield.app.crypto.CryptoInitializer
                                .ensureKeyExists(getApplicationContext());
                            myPriv = com.duoshield.app.crypto.CryptoInitializer
                                .getMyPrivateKey(getApplicationContext());
                            if (myPriv == null) return;
                            String newPub = com.duoshield.app.crypto.CryptoInitializer
                                .getMyPublicKeyB64(getApplicationContext());
                            if (newPub != null) {
                                db.collection("users").document(myUid)
                                  .set(java.util.Collections.singletonMap("ecPublicKey", newPub),
                                       com.google.firebase.firestore.SetOptions.merge());
                            }
                        }
                        javax.crypto.SecretKey sk =
                            com.duoshield.app.crypto.ECDHHelper.deriveSharedKey(myPriv, partnerPub);
                        String b64 = android.util.Base64.encodeToString(
                            sk.getEncoded(), android.util.Base64.NO_WRAP);
                        secure.edit()
                            .putString(com.duoshield.app.crypto.CryptoInitializer.KEY_SHARED_AES, b64)
                            .putString(com.duoshield.app.crypto.CryptoInitializer.KEY_PARTNER_EC_PUBLIC, partnerPub)
                            .apply();
                    } catch (Exception ignored) {}
                }).start();
            });
    }

    private void navigateAfterRestore(SharedPreferences prefs) {
        boolean isPaired = prefs.getBoolean("is_paired", false);
        Intent intent = isPaired
                ? new Intent(this, ConversationListActivity.class)
                : new Intent(this, PairingActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ── Validation ────────────────────────────────────────────────────────────

    private boolean validateSignIn(String email, String pass) {
        clearErrors();
        boolean ok = true;
        if (TextUtils.isEmpty(email)) {
            tilEmail.setError(getString(R.string.error_email_required)); ok = false;
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError(getString(R.string.error_email_invalid)); ok = false;
        }
        if (TextUtils.isEmpty(pass)) {
            tilPassword.setError(getString(R.string.error_passphrase_required)); ok = false;
        }
        return ok;
    }

    private boolean validateSignUp(String email, String pass) {
        clearErrors();
        boolean ok = true;
        if (TextUtils.isEmpty(email)) {
            tilEmail.setError(getString(R.string.error_email_required)); ok = false;
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError(getString(R.string.error_email_invalid)); ok = false;
        }
        if (pass.length() < 8) {
            tilPassword.setError(getString(R.string.error_passphrase_length)); ok = false;
        }
        return ok;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void clearErrors() {
        if (tilEmail    != null) tilEmail.setError(null);
        if (tilPassword != null) tilPassword.setError(null);
        if (tvError     != null) tvError.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        if (tvError != null) { tvError.setText(msg); tvError.setVisibility(View.VISIBLE); }
    }

    private void showSuccess(String msg) {
        if (tvError != null) {
            tvError.setTextColor(getColor(R.color.ds_accent));
            tvError.setText(msg);
            tvError.setVisibility(View.VISIBLE);
        }
    }

    private void copyToClipboard(String label, String text, String toast) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private static void setDialogErr(TextView tv, String msg) {
        tv.setText(msg); tv.setVisibility(View.VISIBLE);
    }

    private static void setDialogBtns(AlertDialog d, boolean enabled) {
        if (d.getButton(AlertDialog.BUTTON_POSITIVE) != null)
            d.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
        if (d.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
            d.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(enabled);
    }

    private void setLoading(boolean on) {
        if (progressSignIn   != null) progressSignIn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (btnAction        != null) btnAction.setEnabled(!on);
        if (tvToggleMode     != null) tvToggleMode.setEnabled(!on);
        if (tvForgotPassword != null) tvForgotPassword.setEnabled(!on);
    }

    private String getEmail()      { return etEmail    != null && etEmail.getText()    != null ? etEmail.getText().toString().trim() : ""; }
    private String getPassphrase() { return etPassword != null && etPassword.getText() != null ? etPassword.getText().toString()     : ""; }
    private static String text(TextInputEditText et) { return et != null && et.getText() != null ? et.getText().toString().trim() : ""; }

    private String friendlyError(String raw) {
        if (raw == null) return getString(R.string.error_generic);
        String lower = raw.toLowerCase();
        if (lower.contains("password is invalid") || lower.contains("no user record") || lower.contains("wrong password"))
            return getString(R.string.error_invalid_credentials);
        if (lower.contains("email address is already in use"))
            return getString(R.string.error_email_in_use);
        if (lower.contains("badly formatted"))
            return getString(R.string.error_email_invalid);
        if (lower.contains("network"))
            return getString(R.string.no_network);
        return raw;
    }

    private interface Callback { void call(String msg); }
}
