package com.duoshield.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
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
import com.duoshield.app.util.WipeHelper;

public class LockScreenActivity extends AppCompatActivity {

    // Bug 17 fix: brute-force protection.
    // Fail counter is stored in plain SharedPreferences (the counter itself is not
    // sensitive). After PIN_WIPE_THRESHOLD failures the entire app is wiped.
    // After PIN_BACKOFF_THRESHOLD failures a growing delay is inserted so online
    // attacks are rate-limited even before the wipe threshold is hit.
    private static final String PREFS_NAME             = "duoshield_prefs";
    private static final String KEY_FAIL_COUNT         = "pin_fail_count";
    private static final int    PIN_BACKOFF_THRESHOLD  = 5;
    private static final int    PIN_WIPE_THRESHOLD     = 10;
    private static final long   BACKOFF_DELAY_MS       = 30_000L; // 30 s per excess attempt

    private EditText etPin;
    private TextView tvError;
    private Button   btnUnlock, btnBiometric;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
        boolean bioEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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
        setInputEnabled(false);
        tvError.setText("Verifying…");
        tvError.setVisibility(View.VISIBLE);

        new Thread(() -> {
            // Both calls are PBKDF2 — must NOT run on the UI thread
            boolean duress  = DuressManager.isDuressPin(this, entered);
            boolean correct = !duress && PinManager.verifyPin(this, entered);

            runOnUiThread(() -> {
                setInputEnabled(true);
                tvError.setVisibility(View.GONE);

                if (duress) {
                    DuressManager.triggerDuress(this);
                } else if (correct) {
                    // Reset fail counter on successful unlock
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit().putInt(KEY_FAIL_COUNT, 0).apply();
                    unlock();
                } else {
                    handleWrongPin();
                }
            });
        }).start();
    }

    /**
     * Bug 17 fix: increments the fail counter and enforces rate-limiting.
     * After PIN_WIPE_THRESHOLD failures the app wipes itself.
     * After PIN_BACKOFF_THRESHOLD failures a delay is inserted before the user
     * can try again (exponential via multiplier on the base delay).
     */
    private void handleWrongPin() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        int failCount = prefs.getInt(KEY_FAIL_COUNT, 0) + 1;
        prefs.edit().putInt(KEY_FAIL_COUNT, failCount).apply();

        if (failCount >= PIN_WIPE_THRESHOLD) {
            // Silent full wipe — same as duress path but triggered by brute-force
            WipeHelper.wipeAll(this);
            return;
        }

        HapticHelper.wrongPin(this);
        Animation shake = AnimationUtils.loadAnimation(this, R.anim.shake);
        etPin.startAnimation(shake);
        etPin.setText("");

        if (failCount > PIN_BACKOFF_THRESHOLD) {
            // §3.5 fix: showing an explicit countdown ("Wait N s…") reveals the existence of
            // backoff logic and then immediately transitions to the decoy screen — a signal to
            // an attacker that (a) rate-limiting is active and (b) the next screen is a decoy.
            // Instead, show a generic "Incorrect PIN" message and silently apply the delay
            // before opening decoy chats, so the backoff is invisible.
            long delay = (failCount - PIN_BACKOFF_THRESHOLD) * BACKOFF_DELAY_MS;
            setInputEnabled(false);
            tvError.setText("Incorrect PIN");
            tvError.setVisibility(View.VISIBLE);
            etPin.postDelayed(() -> {
                setInputEnabled(true);
                tvError.setVisibility(View.GONE);
                openDecoyChats();
            }, delay);
        } else {
            // Short delay so the shake finishes before transitioning
            etPin.postDelayed(this::openDecoyChats, 550);
        }
    }

    private void setInputEnabled(boolean enabled) {
        btnUnlock.setEnabled(enabled);
        btnBiometric.setEnabled(enabled);
        etPin.setEnabled(enabled);
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
