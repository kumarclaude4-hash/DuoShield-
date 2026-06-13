package com.duoshield.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;

public class LockScreenActivity extends AppCompatActivity {

    private EditText etPin;
    private TextView tvError;
    private Button   btnUnlock, btnBiometric;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_lock_screen);

        etPin        = findViewById(R.id.etPin);
        tvError      = findViewById(R.id.tvError);
        btnUnlock    = findViewById(R.id.btnUnlock);
        btnBiometric = findViewById(R.id.btnBiometric);

        // If no app PIN has been set yet, skip the lock screen entirely
        if (!PinManager.hasPinSet(this)) {
            AppLockManager.onAppForegrounded(this);
            finish();
            return;
        }

        // Only show biometric button if enabled and hardware is enrolled
        boolean bioEnabled = getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .getBoolean("biometric_enabled", false);
        if (bioEnabled && BiometricHelper.isAvailable(this)) {
            btnBiometric.setVisibility(View.VISIBLE);
            showBiometric();
        } else {
            btnBiometric.setVisibility(View.GONE);
        }

        btnUnlock.setOnClickListener(v -> checkPin());
        btnBiometric.setOnClickListener(v -> showBiometric());

        etPin.setOnEditorActionListener((v, actionId, event) -> {
            checkPin();
            return true;
        });
    }

    private void showBiometric() {
        BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
            @Override public void onSuccess() { unlock(); }
            @Override public void onFailure() { etPin.requestFocus(); }
        });
    }

    private void checkPin() {
        String entered = etPin.getText().toString().trim();
        if (entered.isEmpty()) {
            tvError.setText("Please enter your PIN.");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        // Disable UI while PBKDF2 runs on background thread (310K iterations ≈ 3–8 s)
        btnUnlock.setEnabled(false);
        btnBiometric.setEnabled(false);
        etPin.setEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);

        new Thread(() -> {
            // Both calls are PBKDF2 — must NOT run on the UI thread
            boolean duress  = DuressManager.isDuressPin(this, entered);
            boolean correct = !duress && PinManager.verifyPin(this, entered);

            runOnUiThread(() -> {
                // Re-enable input regardless of outcome
                btnUnlock.setEnabled(true);
                btnBiometric.setEnabled(true);
                etPin.setEnabled(true);
                tvError.setVisibility(View.GONE);

                if (duress) {
                    DuressManager.triggerDuress(this);
                } else if (correct) {
                    unlock();
                } else {
                    // Wrong PIN → shake + open decoy chats (plausible deniability)
                    HapticHelper.wrongPin(this);
                    Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
                    etPin.startAnimation(shake);
                    etPin.setText("");
                    // Short delay so the shake finishes before transitioning
                    etPin.postDelayed(() -> openDecoyChats(), 550);
                }
            });
        }).start();
    }

    private void unlock() {
        AppLockManager.onAppForegrounded(this);
        finish();
    }

    private void openDecoyChats() {
        AppLockManager.onAppForegrounded(this);
        Intent i = new Intent(this, FakeChatsActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }

    @Override public void onBackPressed() {
        // Block back — user must authenticate
    }
}
