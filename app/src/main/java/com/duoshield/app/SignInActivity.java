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
import com.duoshield.app.ui.PairingActivity;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SignInActivity extends AppCompatActivity {

    private FirebaseAuth            mAuth;
    private TextInputEditText       etEmail, etPassword;
    private TextInputLayout         tilEmail, tilPassword;
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

        mAuth           = FirebaseAuth.getInstance();
        tilEmail        = findViewById(R.id.tilEmail);
        tilPassword     = findViewById(R.id.tilPassword);
        etEmail         = findViewById(R.id.etEmail);
        etPassword      = findViewById(R.id.etPassword);
        btnAction       = findViewById(R.id.btnSignIn);
        tvToggleMode    = findViewById(R.id.tvSignUp);
        tvForgotPassword = findViewById(R.id.tvForgotPassword);
        progressSignIn  = findViewById(R.id.progressSignIn);
        tvError         = findViewById(R.id.tvError);

        btnAction.setOnClickListener(v -> {
            if (isSignUpMode) register();
            else signIn();
        });

        tvToggleMode.setOnClickListener(v -> toggleMode());

        tvForgotPassword.setOnClickListener(v -> showForgotPasswordDialog());
    }

    @Override
    protected void onStart() {
        super.onStart();
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) route(user.getUid());
    }

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

    private void signIn() {
        String email = getEmail();
        String pass  = getPassword();
        if (!validate(email, pass)) return;
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

    private void register() {
        String email = getEmail();
        String pass  = getPassword();
        if (!validate(email, pass)) return;
        setLoading(true);
        mAuth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener(r -> {
                    setLoading(false);
                    route(r.getUser().getUid());
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    showError(friendlyError(e.getMessage()));
                });
    }

    private void showForgotPasswordDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_reset_password, null);
        TextInputEditText etResetEmail = dialogView.findViewById(R.id.etResetEmail);

        String currentEmail = getEmail();
        if (!TextUtils.isEmpty(currentEmail)) {
            etResetEmail.setText(currentEmail);
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.reset_password_title)
                .setMessage(R.string.reset_password_message)
                .setView(dialogView)
                .setPositiveButton(R.string.reset_send, (dialog, which) -> {
                    String resetEmail = etResetEmail.getText() != null
                            ? etResetEmail.getText().toString().trim()
                            : "";
                    if (TextUtils.isEmpty(resetEmail)) {
                        showError(getString(R.string.error_email_required));
                        return;
                    }
                    sendPasswordReset(resetEmail);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void sendPasswordReset(String email) {
        setLoading(true);
        mAuth.sendPasswordResetEmail(email)
                .addOnSuccessListener(unused -> {
                    setLoading(false);
                    clearErrors();
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.reset_password_title)
                            .setMessage(getString(R.string.reset_password_sent, email))
                            .setPositiveButton(R.string.ok, null)
                            .show();
                })
                .addOnFailureListener(e -> {
                    setLoading(false);
                    showError(friendlyError(e.getMessage()));
                });
    }

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

    private boolean validate(String email, String pass) {
        clearErrors();
        boolean ok = true;
        if (TextUtils.isEmpty(email)) {
            tilEmail.setError(getString(R.string.error_email_required));
            ok = false;
        } else if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            tilEmail.setError(getString(R.string.error_email_invalid));
            ok = false;
        }
        if (TextUtils.isEmpty(pass)) {
            tilPassword.setError(getString(R.string.error_password_required));
            ok = false;
        } else if (pass.length() < 6) {
            tilPassword.setError(getString(R.string.error_password_length));
            ok = false;
        }
        return ok;
    }

    private void clearErrors() {
        if (tilEmail    != null) tilEmail.setError(null);
        if (tilPassword != null) tilPassword.setError(null);
        if (tvError     != null) tvError.setVisibility(View.GONE);
    }

    private void showError(String msg) {
        if (tvError != null) {
            tvError.setText(msg);
            tvError.setVisibility(View.VISIBLE);
        }
    }

    private void setLoading(boolean on) {
        if (progressSignIn != null)
            progressSignIn.setVisibility(on ? View.VISIBLE : View.GONE);
        if (btnAction    != null) btnAction.setEnabled(!on);
        if (tvToggleMode != null) tvToggleMode.setEnabled(!on);
        if (tvForgotPassword != null) tvForgotPassword.setEnabled(!on);
    }

    private String getEmail() {
        return etEmail.getText() != null ? etEmail.getText().toString().trim() : "";
    }

    private String getPassword() {
        return etPassword.getText() != null ? etPassword.getText().toString() : "";
    }

    private String friendlyError(String raw) {
        if (raw == null) return getString(R.string.error_generic);
        String lower = raw.toLowerCase();
        if (lower.contains("password is invalid") || lower.contains("no user record"))
            return getString(R.string.error_invalid_credentials);
        if (lower.contains("email address is already in use"))
            return getString(R.string.error_email_in_use);
        if (lower.contains("badly formatted"))
            return getString(R.string.error_email_invalid);
        if (lower.contains("network"))
            return getString(R.string.no_network);
        return raw;
    }
}
