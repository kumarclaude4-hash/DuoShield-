package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import com.duoshield.app.BaseActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.SelfDestructWorker;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.PinManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends BaseActivity {

    private static final String TAG             = "SettingsActivity";
    private static final String WORK_TAG        = "self_destruct_work";
    private static final long   DEFAULT_TTL_MIN = 60L;
    private static final long   MIN_TTL         = 5L;
    private static final long   MAX_TTL         = 10_080L;

    private static final long[]   DISAPPEAR_MS  = {0, 60_000L, 300_000L, 3_600_000L, 86_400_000L, 604_800_000L};
    private static final String[] DISAPPEAR_LBL = {"Off", "1 minute", "5 minutes", "1 hour", "1 day", "1 week"};

    private SharedPreferences prefs;
    private SwitchCompat      switchNotifications, switchSelfDestruct, switchBiometric, switchDarkMode;
    private LinearLayout      layoutMinutes;
    private EditText          editTtlMinutes;
    private EditText          etDuressPin;
    private EditText          etNewPin, etConfirmPin;
    private TextView          textTtlHint, tvPinStatus, tvDisappearSub;
    private Button            btnDisappearing;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        // ── Find views ────────────────────────────────────────────────────────
        switchNotifications = findViewById(R.id.switchNotifications);
        switchSelfDestruct  = findViewById(R.id.switchSelfDestruct);
        switchBiometric     = findViewById(R.id.switchBiometric);
        switchDarkMode      = findViewById(R.id.switchDarkMode);
        layoutMinutes       = findViewById(R.id.layoutMinutes);
        editTtlMinutes      = findViewById(R.id.editTtlMinutes);
        textTtlHint         = findViewById(R.id.textTtlHint);
        etDuressPin         = findViewById(R.id.etDuressPin);
        etNewPin            = findViewById(R.id.etNewPin);
        etConfirmPin        = findViewById(R.id.etConfirmPin);
        tvPinStatus         = findViewById(R.id.tvPinStatus);
        btnDisappearing     = findViewById(R.id.btnDisappearing);
        tvDisappearSub      = findViewById(R.id.tvDisappearSub);

        Button btnSetPin      = findViewById(R.id.btnSetPin);
        Button btnClearPin    = findViewById(R.id.btnClearPin);
        Button btnSetDuressPin = findViewById(R.id.btnSetDuressPin);
        Button btnUnpair       = findViewById(R.id.btnUnpair);

        // ── Restore saved state ───────────────────────────────────────────────
        switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));
        boolean destructOn = prefs.getBoolean("self_destruct_enabled", false);
        switchSelfDestruct.setChecked(destructOn);
        editTtlMinutes.setText(String.valueOf(prefs.getLong("self_destruct_minutes", DEFAULT_TTL_MIN)));
        setMinutesVisible(destructOn);
        switchBiometric.setChecked(prefs.getBoolean("biometric_enabled", false));
        switchDarkMode.setChecked(prefs.getBoolean("dark_mode", false));
        updateDisappearLabel();
        refreshPinStatus();

        // ── App PIN ───────────────────────────────────────────────────────────
        btnSetPin.setOnClickListener(v -> saveAppPin());
        btnClearPin.setOnClickListener(v -> confirmClearPin());

        // ── Duress PIN ────────────────────────────────────────────────────────
        btnSetDuressPin.setOnClickListener(v -> saveDuressPin());

        // ── Biometric ─────────────────────────────────────────────────────────
        switchBiometric.setOnCheckedChangeListener((b, checked) -> {
            if (checked && !PinManager.hasPinSet(this)) {
                switchBiometric.setChecked(false);
                Toast.makeText(this,
                    "Set an app PIN first before enabling biometric lock.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            if (checked && !BiometricHelper.isAvailable(this)) {
                switchBiometric.setChecked(false);
                Toast.makeText(this,
                    "No biometric enrolled on this device. Enroll fingerprint or face in system Settings first.",
                    Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putBoolean("biometric_enabled", checked).apply();
        });

        // ── Other switches ────────────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((b, c) ->
            prefs.edit().putBoolean("notifications_enabled", c).apply());

        switchDarkMode.setOnCheckedChangeListener((b, c) -> {
            prefs.edit().putBoolean("dark_mode", c).apply();
            AppCompatDelegate.setDefaultNightMode(
                c ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
        });

        switchSelfDestruct.setOnCheckedChangeListener((b, c) -> {
            setMinutesVisible(c);
            prefs.edit().putBoolean("self_destruct_enabled", c).apply();
            if (c) saveTtlAndSchedule(); else cancelSelfDestruct();
        });

        editTtlMinutes.setOnFocusChangeListener((v, f) -> {
            if (!f && switchSelfDestruct.isChecked()) saveTtlAndSchedule();
        });

        btnDisappearing.setOnClickListener(v -> showDisappearingPicker());
        btnUnpair.setOnClickListener(v -> confirmUnpair());
    }

    // ── App PIN logic ─────────────────────────────────────────────────────────

    private void saveAppPin() {
        String pin     = etNewPin.getText().toString().trim();
        String confirm = etConfirmPin.getText().toString().trim();

        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "PIN must be 4–6 digits.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!pin.equals(confirm)) {
            Toast.makeText(this, "PINs don't match. Try again.", Toast.LENGTH_SHORT).show();
            etConfirmPin.setText("");
            return;
        }

        // Disable the button to prevent double-taps while PBKDF2 runs in background
        Button btnSetPin = findViewById(R.id.btnSetPin);
        if (btnSetPin != null) btnSetPin.setEnabled(false);
        tvPinStatus.setText("Saving PIN…");

        bgExecutor.execute(() -> {
            // isDuressPin + setPin both run PBKDF2 — must NOT run on the UI thread
            boolean clashWithDuress = DuressManager.isDuressPin(this, pin);
            if (!clashWithDuress) PinManager.setPin(this, pin);

            runOnUiThread(() -> {
                if (btnSetPin != null) btnSetPin.setEnabled(true);
                if (clashWithDuress) {
                    tvPinStatus.setText("No PIN set — lock screen is disabled");
                    Toast.makeText(this,
                        "App PIN cannot be the same as your duress PIN.",
                        Toast.LENGTH_LONG).show();
                } else {
                    etNewPin.setText("");
                    etConfirmPin.setText("");
                    refreshPinStatus();
                    Toast.makeText(this, "App PIN set successfully.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void confirmClearPin() {
        if (!PinManager.hasPinSet(this)) {
            Toast.makeText(this, "No app PIN is set.", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Clear App PIN")
            .setMessage("This will remove your app PIN. The lock screen will no longer appear. Continue?")
            .setPositiveButton("Clear", (d, w) -> {
                PinManager.clearPin(this);
                // Also disable biometric since there's no fallback PIN now
                prefs.edit().putBoolean("biometric_enabled", false).apply();
                switchBiometric.setChecked(false);
                refreshPinStatus();
                Toast.makeText(this, "App PIN cleared.", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void refreshPinStatus() {
        if (tvPinStatus == null) return;
        tvPinStatus.setText(PinManager.hasPinSet(this)
            ? "✓  App PIN is set"
            : "No PIN set — lock screen is disabled");
        tvPinStatus.setTextColor(getResources().getColor(
            PinManager.hasPinSet(this) ? R.color.online_green : R.color.text_hint, null));
    }

    // ── Duress PIN logic ──────────────────────────────────────────────────────

    private void saveDuressPin() {
        String pin = etDuressPin.getText().toString().trim();
        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "Duress PIN must be 4–6 digits.", Toast.LENGTH_SHORT).show();
            return;
        }

        // Disable button to prevent double-tap while PBKDF2 runs in background
        Button btnSetDuressPin = findViewById(R.id.btnSetDuressPin);
        if (btnSetDuressPin != null) btnSetDuressPin.setEnabled(false);

        bgExecutor.execute(() -> {
            // verifyPin + setDuressPin both run PBKDF2 — must NOT run on the UI thread
            boolean clashWithAppPin = PinManager.verifyPin(this, pin);
            if (!clashWithAppPin) DuressManager.setDuressPin(this, pin);

            runOnUiThread(() -> {
                if (btnSetDuressPin != null) btnSetDuressPin.setEnabled(true);
                if (clashWithAppPin) {
                    Toast.makeText(this,
                        "Duress PIN cannot match your app PIN.",
                        Toast.LENGTH_LONG).show();
                } else {
                    etDuressPin.setText("");
                    Toast.makeText(this, "Duress PIN set. Keep it secret.", Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    // ── Disappearing messages ─────────────────────────────────────────────────

    private void showDisappearingPicker() {
        long current = prefs.getLong("disappear_ms", 0);
        int checked = 0;
        for (int i = 0; i < DISAPPEAR_MS.length; i++) {
            if (DISAPPEAR_MS[i] == current) { checked = i; break; }
        }
        new AlertDialog.Builder(this)
            .setTitle("Disappearing messages")
            .setSingleChoiceItems(DISAPPEAR_LBL, checked, (d, which) -> {
                long ms = DISAPPEAR_MS[which];
                prefs.edit().putLong("disappear_ms", ms).apply();
                scheduleOrCancelDestruct(ms);
                updateDisappearLabel();
                d.dismiss();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void scheduleOrCancelDestruct(long ms) {
        WorkManager wm = WorkManager.getInstance(getApplicationContext());
        if (ms <= 0) {
            wm.cancelAllWorkByTag(WORK_TAG);
        } else {
            wm.enqueueUniquePeriodicWork(
                WORK_TAG,
                ExistingPeriodicWorkPolicy.REPLACE,
                new PeriodicWorkRequest.Builder(SelfDestructWorker.class, 15, TimeUnit.MINUTES)
                    .addTag(WORK_TAG).build());
        }
    }

    private void updateDisappearLabel() {
        if (btnDisappearing == null) return;
        long ttl = prefs.getLong("disappear_ms", 0);
        String label = "Off";
        for (int i = 0; i < DISAPPEAR_MS.length; i++) {
            if (DISAPPEAR_MS[i] == ttl) { label = DISAPPEAR_LBL[i]; break; }
        }
        btnDisappearing.setText(label);
        if (tvDisappearSub != null) {
            if (ttl <= 0) {
                tvDisappearSub.setText("New messages will not automatically disappear.");
            } else {
                tvDisappearSub.setText("New messages will disappear after " + label + ".");
            }
        }
    }

    // ── Self-destruct / auto-delete ───────────────────────────────────────────

    private void setMinutesVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        layoutMinutes.setVisibility(vis);
        textTtlHint.setVisibility(vis);
    }

    private void saveTtlAndSchedule() {
        String raw = editTtlMinutes.getText().toString().trim();
        long minutes;
        try { minutes = Long.parseLong(raw); } catch (NumberFormatException e) { minutes = DEFAULT_TTL_MIN; }
        minutes = Math.max(MIN_TTL, Math.min(MAX_TTL, minutes));
        editTtlMinutes.setText(String.valueOf(minutes));
        prefs.edit().putLong("self_destruct_minutes", minutes).apply();
        PeriodicWorkRequest workRequest =
            new PeriodicWorkRequest.Builder(SelfDestructWorker.class, 15, TimeUnit.MINUTES)
                .addTag(WORK_TAG).build();
        WorkManager.getInstance(getApplicationContext())
                   .enqueueUniquePeriodicWork(WORK_TAG, ExistingPeriodicWorkPolicy.REPLACE, workRequest);
        Toast.makeText(this, "Auto-delete set to " + minutes + " minutes", Toast.LENGTH_SHORT).show();
    }

    private void cancelSelfDestruct() {
        WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);
        Toast.makeText(this, "Auto-delete disabled", Toast.LENGTH_SHORT).show();
    }

    // ── Unpair ────────────────────────────────────────────────────────────────

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
            .setTitle("Unpair Device")
            .setMessage("This will remove all pairing data and delete all local messages. This cannot be undone.")
            .setPositiveButton("Unpair", (dialog, which) -> unpairDevice())
            .setNegativeButton("Cancel", null).show();
    }

    private void unpairDevice() {
        WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);
        String conversationId = prefs.getString("conversation_id", null);
        bgExecutor.execute(() -> {
            try {
                if (conversationId != null)
                    AppDatabase.getInstance(getApplicationContext()).messageDao().deleteAll(conversationId);
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                prefs.edit().clear().apply();
                Toast.makeText(this, "Device unpaired.", Toast.LENGTH_SHORT).show();
                Intent i = new Intent(this, PairingActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(i); finish();
            });
        });
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
