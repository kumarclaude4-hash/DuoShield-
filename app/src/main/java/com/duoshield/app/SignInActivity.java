package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
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

    private TextInputLayout         tilEmail, tilPassword, tilRecoveryPhrase;
    private TextInputEditText       etEmail, etPassword, etRecoveryPhrase;
    private MaterialButton          btnAction;
    private TextView                tvToggleMode, tvForgotPassword, tvError, tvRecoveryNote;
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

        tilEmail          = findViewById(R.id.tilEmail);
        tilPassword       = findViewById(R.id.tilPassword);
        tilRecoveryPhrase = findViewById(R.id.tilRecoveryPhrase);
        etEmail           = findViewById(R.id.etEmail);
        etPassword        = findViewById(R.id.etPassword);
        etRecoveryPhrase  = findViewById(R.id.etRecoveryPhrase);
        btnAction         = findViewById(R.id.btnSignIn);
        tvToggleMode      = findViewById(R.id.tvSignUp);
        tvForgotPassword  = findViewById(R.id.tvForgotPassword);
        tvRecoveryNote    = findViewById(R.id.tvRecoveryNote);
        progressSignIn    = findViewById(R.id.progressSignIn);
        tvError           = findViewById(R.id.tvError);

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
            tilRecoveryPhrase.setVisibility(View.VISIBLE);
            tvRecoveryNote.setVisibility(View.VISIBLE);
            tvForgotPassword.setVisibility(View.GONE);
        } else {
            btnAction.setText(R.string.sign_in);
            tvToggleMode.setText(R.string.no_account);
            tilRecoveryPhrase.setVisibility(View.GONE);
            tvRecoveryNote.setVisibility(View.GONE);
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
        String email    = getEmail();
        String pass     = getPassphrase();
        String recovery = getRecoveryPhrase();
        if (!validateSignUp(email, pass, recovery)) return;
        setLoading(true);
        mAuth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    String uid = r.getUser().getUid();
                    storeRecoveryBlob(email, pass, recovery, uid,
                            () -> { setLoading(false); route(uid); },
                            err -> { setLoading(false); showError(err); });
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    showError(friendlyError(e.getMessage()));
                });
    }

    /** Encrypt loginPassphrase with recoveryPassphrase and persist in Firestore. */
    private void storeRecoveryBlob(String email, String loginPass, String recoveryPass,
                                   String uid, Runnable onSuccess, Callback onError) {
        new Thread(() -> {
            try {
                String emailHash = RecoveryHelper.emailHash(email);
                String blob      = RecoveryHelper.encryptLoginPassphrase(loginPass, recoveryPass);

                Map<String, Object> doc = new HashMap<>();
                doc.put("enc", blob);
                doc.put("uid", uid);

                db.collection("recovery").document(emailHash)
                        .set(doc)
                        .addOnSuccessListener(v -> runOnUiThread(onSuccess))
                        .addOnFailureListener(e -> runOnUiThread(() ->
                                onError.call("Account created but recovery setup failed: "
                                        + e.getMessage())));
            } catch (Exception e) {
                runOnUiThread(() -> onError.call("Recovery encryption error: " + e.getMessage()));
            }
        }).start();
    }

    // ── Forgot passphrase dialog ──────────────────────────────────────────────

    private void showForgotPassphraseDialog() {
        View dialogView = getLayoutInflater()
                .inflate(R.layout.dialog_forgot_passphrase, null);

        TextInputEditText etResetEmail     = dialogView.findViewById(R.id.etResetEmail);
        TextInputEditText etResetRecovery  = dialogView.findViewById(R.id.etResetRecovery);
        TextInputEditText etNewPass        = dialogView.findViewById(R.id.etNewPassphrase);
        TextInputEditText etConfirmPass    = dialogView.findViewById(R.id.etConfirmPassphrase);
        TextView          tvResetError     = dialogView.findViewById(R.id.tvResetError);

        String currentEmail = getEmail();
        if (!TextUtils.isEmpty(currentEmail)) etResetEmail.setText(currentEmail);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.reset_passphrase_title)
                .setView(dialogView)
                .setPositiveButton(R.string.reset_apply, null)
                .setNegativeButton(R.string.cancel, null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String email    = text(etResetEmail);
                String recovery = text(etResetRecovery);
                String newPass  = text(etNewPass);
                String confirm  = text(etConfirmPass);

                tvResetError.setVisibility(View.GONE);

                if (TextUtils.isEmpty(email)) {
                    showDialogError(tvResetError, getString(R.string.error_email_required));
                    return;
                }
                if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
                    showDialogError(tvResetError, getString(R.string.error_email_invalid));
                    return;
                }
                if (TextUtils.isEmpty(recovery)) {
                    showDialogError(tvResetError, getString(R.string.error_recovery_required));
                    return;
                }
                if (newPass.length() < 8) {
                    showDialogError(tvResetError, getString(R.string.error_passphrase_length));
                    return;
                }
                if (!newPass.equals(confirm)) {
                    showDialogError(tvResetError, getString(R.string.error_passphrases_no_match));
                    return;
                }

                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);

                performReset(email, recovery, newPass, tvResetError, dialog);
            });
        });

        dialog.show();
    }

    /**
     * Full passphrase reset — no email link, no OTP:
     *  1. Fetch recovery blob from Firestore (public read)
     *  2. Decrypt old passphrase using recovery passphrase
     *  3. Re-authenticate with old passphrase
     *  4. Update to new passphrase
     *  5. Re-encrypt new passphrase and update Firestore
     */
    private void performReset(String email, String recoveryPass, String newPass,
                               TextView tvResetError, AlertDialog dialog) {
        new Thread(() -> {
            try {
                String emailHash = RecoveryHelper.emailHash(email);

                db.collection("recovery").document(emailHash).get()
                        .addOnSuccessListener(snap -> {
                            if (!snap.exists()) {
                                runOnUiThread(() -> {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                    showDialogError(tvResetError,
                                            getString(R.string.error_no_recovery_found));
                                });
                                return;
                            }

                            String blob = snap.getString("enc");
                            if (TextUtils.isEmpty(blob)) {
                                runOnUiThread(() -> {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                    showDialogError(tvResetError,
                                            getString(R.string.error_recovery_corrupt));
                                });
                                return;
                            }

                            new Thread(() -> {
                                try {
                                    String oldPass = RecoveryHelper
                                            .decryptLoginPassphrase(blob, recoveryPass);
                                    runOnUiThread(() ->
                                            reauthAndUpdate(email, oldPass, newPass,
                                                    emailHash, recoveryPass,
                                                    tvResetError, dialog));
                                } catch (Exception e) {
                                    runOnUiThread(() -> {
                                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                                        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                                        showDialogError(tvResetError,
                                                getString(R.string.error_recovery_wrong));
                                    });
                                }
                            }).start();
                        })
                        .addOnFailureListener(e -> runOnUiThread(() -> {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                            showDialogError(tvResetError, friendlyError(e.getMessage()));
                        }));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    showDialogError(tvResetError, friendlyError(e.getMessage()));
                });
            }
        }).start();
    }

    private void reauthAndUpdate(String email, String oldPass, String newPass,
                                  String emailHash, String recoveryPass,
                                  TextView tvResetError, AlertDialog dialog) {
        mAuth.signInWithEmailAndPassword(email, oldPass)
                .addOnSuccessListener(r -> {
                    FirebaseUser user = r.getUser();
                    if (user == null) {
                        re_enable(dialog);
                        showDialogError(tvResetError, getString(R.string.error_generic));
                        return;
                    }
                    user.updatePassword(newPass)
                            .addOnSuccessListener(v -> {
                                updateRecoveryBlob(emailHash, newPass, recoveryPass);
                                dialog.dismiss();
                                showSuccess(getString(R.string.reset_passphrase_success));
                            })
                            .addOnFailureListener(e -> {
                                re_enable(dialog);
                                showDialogError(tvResetError, friendlyError(e.getMessage()));
                            });
                })
                .addOnFailureListener(e -> {
                    re_enable(dialog);
                    showDialogError(tvResetError, getString(R.string.error_recovery_wrong));
                });
    }

    /** Re-encrypt new passphrase with recovery passphrase and update Firestore. */
    private void updateRecoveryBlob(String emailHash, String newPass, String recoveryPass) {
        new Thread(() -> {
            try {
                String newBlob = RecoveryHelper.encryptLoginPassphrase(newPass, recoveryPass);
                Map<String, Object> update = new HashMap<>();
                update.put("enc", newBlob);
                db.collection("recovery").document(emailHash).update(update);
            } catch (Exception ignored) {
            }
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

    private boolean validateSignUp(String email, String pass, String recovery) {
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
        if (TextUtils.isEmpty(recovery)) {
            tilRecoveryPhrase.setError(getString(R.string.error_recovery_required)); ok = false;
        } else if (recovery.length() < 8) {
            tilRecoveryPhrase.setError(getString(R.string.error_recovery_length)); ok = false;
        }
        if (pass.equals(recovery)) {
            tilRecoveryPhrase.setError(getString(R.string.error_recovery_same_as_pass)); ok = false;
        }
        return ok;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void clearErrors() {
        if (tilEmail          != null) tilEmail.setError(null);
        if (tilPassword       != null) tilPassword.setError(null);
        if (tilRecoveryPhrase != null) tilRecoveryPhrase.setError(null);
        if (tvError           != null) tvError.setVisibility(View.GONE);
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

    private static void showDialogError(TextView tv, String msg) {
        tv.setText(msg);
        tv.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean on) {
        if (progressSignIn   != null) progressSignIn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (btnAction        != null) btnAction.setEnabled(!on);
        if (tvToggleMode     != null) tvToggleMode.setEnabled(!on);
        if (tvForgotPassword != null) tvForgotPassword.setEnabled(!on);
    }

    private static void re_enable(AlertDialog dialog) {
        if (dialog.getButton(AlertDialog.BUTTON_POSITIVE) != null)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
        if (dialog.getButton(AlertDialog.BUTTON_NEGATIVE) != null)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
    }

    private String getEmail()         { return etEmail        != null && etEmail.getText()        != null ? etEmail.getText().toString().trim()        : ""; }
    private String getPassphrase()    { return etPassword     != null && etPassword.getText()     != null ? etPassword.getText().toString()            : ""; }
    private String getRecoveryPhrase(){ return etRecoveryPhrase != null && etRecoveryPhrase.getText() != null ? etRecoveryPhrase.getText().toString() : ""; }
    private static String text(TextInputEditText et) { return et != null && et.getText() != null ? et.getText().toString().trim() : ""; }

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
