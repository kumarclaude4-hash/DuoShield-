package com.duoshield.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.WipeHelper;

public class LockScreenActivity extends AppCompatActivity {

    private static final int MAX_ATTEMPTS = 5;

    private EditText etPin;
    private TextView tvError;
    private Button   btnUnlock, btnBiometric;
    private int      failedAttempts = 0;

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

        // Only show biometric button if biometrics are enrolled and feature is enabled
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

        // Allow PIN entry on keyboard "done"
        etPin.setOnEditorActionListener((v, actionId, event) -> {
            checkPin();
            return true;
        });
    }

    private void showBiometric() {
        BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
            @Override public void onSuccess() { unlock(); }
            @Override public void onFailure() {
                // Biometric unavailable or user tapped "Use PIN" — focus PIN field
                etPin.requestFocus();
            }
        });
    }

    private void checkPin() {
        String entered = etPin.getText().toString().trim();
        if (entered.isEmpty()) {
            tvError.setText("Please enter your PIN.");
            tvError.setVisibility(View.VISIBLE);
            return;
        }

        // Duress PIN check — must happen before normal PIN check
        if (DuressManager.isDuressPin(this, entered)) {
            DuressManager.triggerDuress(this);
            return;
        }

        if (PinManager.verifyPin(this, entered)) {
            failedAttempts = 0;
            unlock();
        } else {
            failedAttempts++;
            HapticHelper.wrongPin(this);
            etPin.setText("");

            int remaining = MAX_ATTEMPTS - failedAttempts;
            if (remaining <= 0) {
                // Too many wrong attempts — wipe everything
                tvError.setText("Too many wrong attempts. Wiping data…");
                tvError.setVisibility(View.VISIBLE);
                btnUnlock.setEnabled(false);
                etPin.setEnabled(false);
                etPin.postDelayed(() -> WipeHelper.wipeAll(this), 1500);
            } else {
                tvError.setText("Wrong PIN. " + remaining + " attempt"
                        + (remaining == 1 ? "" : "s") + " remaining.");
                tvError.setVisibility(View.VISIBLE);
            }
        }
    }

    private void unlock() {
        AppLockManager.onAppForegrounded(this);
        finish();
    }

    @Override public void onBackPressed() {
        // Block back — user must authenticate to proceed
    }
}
