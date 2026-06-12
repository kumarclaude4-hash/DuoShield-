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

        btnAction.setOnClickListener(v -> {
            if (isSignUpMode) register();
            else signIn();
        });
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
                    route(r.getUser().getUid());
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    showError(friendlyError(e.getMessage()));
                });
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
                    String uid = r.getUser().getUid();
                    // Generate recovery code and show it before routing
                    String recoveryCode = RecoveryHelper.generateRecoveryCode();
                    showRecoveryCodeDialog(email, pass, recoveryCode, uid);
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    showError(friendlyError(e.getMessage()));
                });
    }

    // ── Recovery code dialog (shown ONCE at signup) ───────────────────────────

    private void showRecoveryCodeDialog(String email, String loginPass,
                                         String recoveryCode, String uid) {
        View dialogView = getLayoutInflater()
                .inflate(R.layout.dialog_show_recovery, null);

        TextView     tvCode   = dialogView.findViewById(R.id.tvRecoveryCode);
        MaterialButton btnCopy = dialogView.findViewById(R.id.btnCopyCode);
        CheckBox     cbSaved  = dialogView.findViewById(R.id.cbSaved);

        tvCode.setText(recoveryCode);

        btnCopy.setOnClickListener(v -> {
            ClipboardManager cm = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("recovery_code", recoveryCode));
            Toast.makeText(this, R.string.recovery_code_copied, Toast.LENGTH_SHORT).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.recovery_code_title)
                .setView(dialogView)
                .setCancelable(false)
                .setPositiveButton(R.string.recovery_code_continue, null)
                .create();

        dialog.setOnShowListener(d -> {
            MaterialButton continueBtn = (MaterialButton)
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            continueBtn.setEnabled(false);

            cbSaved.setOnCheckedChangeListener((btn, checked) ->
                    continueBtn.setEnabled(checked));

            continueBtn.setOnClickListener(v -> {
                dialog.dismiss();
                setLoading(true);
                storeRecoveryBlob(email, loginPass, recoveryCode, uid,
                        () -> { setLoading(false); route(uid); },
                        err -> { setLoading(false); showError(err); });
            });
        });

        dialog.show();
    }

    // ── Persist recovery blob in Firestore ────────────────────────────────────

    private void storeRecoveryBlob(String email, String loginPass, String recoveryCode,
                                    String uid, Runnable onSuccess, Callback onError) {
        new Thread(() -> {
            try {
                String emailHash = RecoveryHelper.emailHash(email);
                String blob      = RecoveryHelper.encryptLoginPassphrase(loginPass, recoveryCode);

                Map<String, Object> doc = new HashMap<>();
                doc.put("enc", blob);
                doc.put("uid", uid);

                db.collection("recovery").document(emailHash)
                        .set(doc)
                        .addOnSuccessListener(v -> runOnUiThread(onSuccess))
                        .addOnFailureListener(e -> runOnUiThread(() ->
                                onError.call(getString(R.string.error_generic))));
            } catch (Exception e) {
                runOnUiThread(() -> onError.call(getString(R.string.error_generic)));
            }
        }).start();
    }

    // ── Forgot passphrase dialog ──────────────────────────────────────────────

    private void showForgotPassphraseDialog() {
        View dialogView = getLayoutInflater()
                .inflate(R.layout.dialog_forgot_passphrase, null);

        TextInputEditText etResetEmail    = dialogView.findViewById(R.id.etResetEmail);
        TextInputEditText etResetRecovery = dialogView.findViewById(R.id.etResetRecovery);
        TextInputEditText etNewPass       = dialogView.findViewById(R.id.etNewPassphrase);
        TextInputEditText etConfirmPass   = dialogView.findViewById(R.id.etConfirmPassphrase);
        TextView          tvResetError    = dialogView.findViewById(R.id.tvResetError);

        String currentEmail = getEmail();
        if (!TextUtils.isEmpty(currentEmail)) etResetEmail.setText(currentEmail);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reset_passphrase_title)
                .setView(dialogView)
                .setPositiveButton(R.string.reset_apply, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                    String email    = text(etResetEmail);
                    String recovery = text(etResetRecovery);
                    String newPass  = text(etNewPass);
                    String confirm  = text(etConfirmPass);

                    tvResetError.setVisibility(View.GONE);

                    if (TextUtils.isEmpty(email)) {
                        setDialogError(tvResetError, getString(R.string.error_email_required));
                        return;
                    }
                    if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                        setDialogError(tvResetError, getString(R.string.error_email_invalid));
                        return;
                    }
                    if (TextUtils.isEmpty(recovery)) {
                        setDialogError(tvResetError, getString(R.string.error_recovery_required));
                        return;
                    }
                    if (newPass.length() < 8) {
                        setDialogError(tvResetError, getString(R.string.error_passphrase_length));
                        return;
                    }
                    if (!newPass.equals(confirm)) {
                        setDialogError(tvResetError, getString(R.string.error_passphrases_no_match));
                        return;
                    }

                    setDialogButtons(dialog, false);
                    performReset(email, recovery, newPass, tvResetError, dialog);
                }));

        dialog.show();
    }

    /**
     * Passphrase reset — no email, no OTP:
     * 1. Fetch encrypted login passphrase from Firestore (public read, doc keyed by SHA-256 email)
     * 2. Decrypt with the recovery code (AES-GCM + PBKDF2) → old passphrase
     * 3. Re-authenticate with old passphrase
     * 4. Update to new passphrase
     * 5. Re-encrypt new passphrase under same recovery code → update Firestore
     */
    private void performReset(String email, String recoveryCode, String newPass,
                               TextView tvResetError, AlertDialog dialog) {
        new Thread(() -> {
            try {
                String emailHash = RecoveryHelper.emailHash(email);
                db.collection("recovery").document(emailHash).get()
                        .addOnSuccessListener(snap -> {
                            if (!snap.exists() || TextUtils.isEmpty(snap.getString("enc"))) {
                                runOnUiThread(() -> {
                                    setDialogButtons(dialog, true);
                                    setDialogError(tvResetError,
                                            getString(!snap.exists()
                                                    ? R.string.error_no_recovery_found
                                                    : R.string.error_recovery_corrupt));
                                });
                                return;
                            }
                            String blob = snap.getString("enc");
                            new Thread(() -> {
                                try {
                                    String oldPass = RecoveryHelper
                                            .decryptLoginPassphrase(blob, recoveryCode);
                                    runOnUiThread(() -> reauthAndUpdate(
                                            email, oldPass, newPass,
                                            emailHash, recoveryCode,
                                            tvResetError, dialog));
                                } catch (Exception e) {
                                    runOnUiThread(() -> {
                                        setDialogButtons(dialog, true);
                                        setDialogError(tvResetError,
                                                getString(R.string.error_recovery_wrong));
                                    });
                                }
                            }).start();
                        })
                        .addOnFailureListener(e -> runOnUiThread(() -> {
                            setDialogButtons(dialog, true);
                            setDialogError(tvResetError, friendlyError(e.getMessage()));
                        }));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setDialogButtons(dialog, true);
                    setDialogError(tvResetError, friendlyError(e.getMessage()));
                });
            }
        }).start();
    }

    private void reauthAndUpdate(String email, String oldPass, String newPass,
                                  String emailHash, String recoveryCode,
                                  TextView tvResetError, AlertDialog dialog) {
        mAuth.signInWithEmailAndPassword(email, oldPass)
                .addOnSuccessListener(r -> {
                    FirebaseUser user = r.getUser();
                    if (user == null) {
                        setDialogButtons(dialog, true);
                        setDialogError(tvResetError, getString(R.string.error_generic));
                        return;
                    }
                    user.updatePassword(newPass)
                            .addOnSuccessListener(v -> {
                                updateRecoveryBlob(emailHash, newPass, recoveryCode);
                                dialog.dismiss();
                                showSuccess(getString(R.string.reset_passphrase_success));
                            })
                            .addOnFailureListener(e -> {
                                setDialogButtons(dialog, true);
                                setDialogError(tvResetError, friendlyError(e.getMessage()));
                            });
                })
                .addOnFailureListener(e -> {
                    setDialogButtons(dialog, true);
                    setDialogError(tvResetError, getString(R.string.error_recovery_wrong));
                });
    }

    private void updateRecoveryBlob(String emailHash, String newPass, String recoveryCode) {
        new Thread(() -> {
            try {
                String newBlob = RecoveryHelper.encryptLoginPassphrase(newPass, recoveryCode);
                Map<String, Object> update = new HashMap<>();
                update.put("enc", newBlob);
                db.collection("recovery").document(emailHash).update(update);
            } catch (Exception ignored) { }
        }).start();
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    private void route(String uid) {
        CryptoInitializer.ensureKeyExists(this);
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putString("my_uid", uid).apply();
        FcmTokenHelper.register(this);
        AppLockManager.onAppForegrounded(this);
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

    private static void setDialogError(TextView tv, String msg) {
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    private static void setDialogButtons(AlertDialog d, boolean enabled) {
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

    private static String text(TextInputEditText et) {
        return et != null && et.getText() != null ? et.getText().toString().trim() : "";
    }

    private String friendlyError(String raw) {
        if (raw == null) return getString(R.string.error_generic);
        String lower = raw.toLowerCase();
        if (lower.contains("password is invalid") || lower.contains("no user record")
                || lower.contains("wrong password"))
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
