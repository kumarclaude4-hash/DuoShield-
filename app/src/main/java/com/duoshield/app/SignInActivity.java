package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.crypto.CryptoInitializer;
import com.duoshield.app.ui.PairingActivity;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.FcmTokenHelper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SignInActivity extends AppCompatActivity {

    private FirebaseAuth mAuth;
    private EditText     etEmail, etPassword;
    private Button       btnSignIn, btnRegister;
    private ProgressBar  progressBar;
    private TextView     tvError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_sign_in);

        mAuth       = FirebaseAuth.getInstance();
        etEmail     = findViewById(R.id.etEmail);
        etPassword  = findViewById(R.id.etPassword);
        btnSignIn   = findViewById(R.id.btnSignIn);
        btnRegister = findViewById(R.id.btnRegister);
        progressBar = findViewById(R.id.progressBar);
        tvError     = findViewById(R.id.tvError);

        btnSignIn.setOnClickListener(v -> signIn());
        btnRegister.setOnClickListener(v -> register());
    }

    @Override
    protected void onStart() {
        super.onStart();
        FirebaseUser user = mAuth.getCurrentUser();
        if (user != null) route(user.getUid());
    }

    private void signIn() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPassword.getText().toString();
        if (!validate(email, pass)) return;
        setLoading(true);
        mAuth.signInWithEmailAndPassword(email, pass)
            .addOnSuccessListener(r -> {
                setLoading(false);
                route(r.getUser().getUid());
            })
            .addOnFailureListener(e -> {
                setLoading(false);
                showError(e.getMessage());
            });
    }

    private void register() {
        String email = etEmail.getText().toString().trim();
        String pass  = etPassword.getText().toString();
        if (!validate(email, pass)) return;
        setLoading(true);
        mAuth.createUserWithEmailAndPassword(email, pass)
            .addOnSuccessListener(r -> {
                setLoading(false);
                route(r.getUser().getUid());
            })
            .addOnFailureListener(e -> {
                setLoading(false);
                showError(e.getMessage());
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
        if (email.isEmpty()) { showError("Enter your email."); return false; }
        if (pass.length() < 6) { showError("Password must be at least 6 characters."); return false; }
        tvError.setVisibility(View.GONE);
        return true;
    }

    private void showError(String msg) {
        tvError.setText(msg);
        tvError.setVisibility(View.VISIBLE);
    }

    private void setLoading(boolean loading) {
        progressBar.setVisibility(loading ? View.VISIBLE : View.GONE);
        btnSignIn.setEnabled(!loading);
        btnRegister.setEnabled(!loading);
    }
}
