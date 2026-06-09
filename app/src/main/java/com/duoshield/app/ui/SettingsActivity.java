package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.db.SelfDestructWorker;
import com.duoshield.app.security.DuressManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {

    private static final String TAG              = "SettingsActivity";
    private static final String WORK_TAG         = "self_destruct_work";
    private static final long   DEFAULT_TTL_MIN  = 60L;
    private static final long   MIN_TTL          = 5L;
    private static final long   MAX_TTL          = 10_080L; // 7 days

    private SharedPreferences prefs;
    private SwitchCompat      switchNotifications;
    private SwitchCompat      switchSelfDestruct;
    private SwitchCompat      switchBiometric;
    private LinearLayout      layoutMinutes;
    private EditText          editTtlMinutes;
    private EditText          etDuressPin;
    private TextView          textTtlHint;

    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Toolbar with back arrow
        Toolbar toolbar = findViewById(R.id.settingsToolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        switchNotifications = findViewById(R.id.switchNotifications);
        switchSelfDestruct  = findViewById(R.id.switchSelfDestruct);
        switchBiometric     = findViewById(R.id.switchBiometric);
        layoutMinutes       = findViewById(R.id.layoutMinutes);
        editTtlMinutes      = findViewById(R.id.editTtlMinutes);
        textTtlHint         = findViewById(R.id.textTtlHint);
        etDuressPin         = findViewById(R.id.etDuressPin);
        Button btnSetDuressPin = findViewById(R.id.btnSetDuressPin);
        Button btnUnpair    = findViewById(R.id.btnUnpair);

        // ── Restore saved state ──────────────────────────────────────────────
        switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));
        boolean destructOn = prefs.getBoolean("self_destruct_enabled", false);
        switchSelfDestruct.setChecked(destructOn);
        editTtlMinutes.setText(String.valueOf(prefs.getLong("self_destruct_minutes", DEFAULT_TTL_MIN)));
        setMinutesVisible(destructOn);
        switchBiometric.setChecked(prefs.getBoolean("biometric_enabled", false));

        // ── Notifications toggle ─────────────────────────────────────────────
        switchNotifications.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("notifications_enabled", checked).apply()
        );

        // ── Self-destruct toggle ─────────────────────────────────────────────
        switchSelfDestruct.setOnCheckedChangeListener((btn, checked) -> {
            setMinutesVisible(checked);
            prefs.edit().putBoolean("self_destruct_enabled", checked).apply();
            if (checked) {
                saveTtlAndSchedule();
            } else {
                cancelSelfDestruct();
            }
        });

        // Save TTL when user leaves the field
        editTtlMinutes.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus && switchSelfDestruct.isChecked()) {
                saveTtlAndSchedule();
            }
        });

        // ── Biometric toggle ─────────────────────────────────────────────────
        switchBiometric.setOnCheckedChangeListener((btn, checked) ->
            prefs.edit().putBoolean("biometric_enabled", checked).apply()
        );

        // ── Duress PIN ───────────────────────────────────────────────────────
        btnSetDuressPin.setOnClickListener(v -> {
            String pin = etDuressPin.getText().toString().trim();
            if (pin.length() < 4) {
                Toast.makeText(this, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show();
                return;
            }
            DuressManager.setDuressPin(this, pin);
            etDuressPin.setText("");
            Toast.makeText(this, "Duress PIN set", Toast.LENGTH_SHORT).show();
        });

        // ── Unpair button ────────────────────────────────────────────────────
        btnUnpair.setOnClickListener(v -> confirmUnpair());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void setMinutesVisible(boolean visible) {
        int vis = visible ? View.VISIBLE : View.GONE;
        layoutMinutes.setVisibility(vis);
        textTtlHint.setVisibility(vis);
    }

    private void saveTtlAndSchedule() {
        String raw = editTtlMinutes.getText().toString().trim();
        long minutes;
        try {
            minutes = Long.parseLong(raw);
        } catch (NumberFormatException e) {
            minutes = DEFAULT_TTL_MIN;
        }

        // Clamp to valid range
        minutes = Math.max(MIN_TTL, Math.min(MAX_TTL, minutes));
        editTtlMinutes.setText(String.valueOf(minutes));

        prefs.edit().putLong("self_destruct_minutes", minutes).apply();

        // Schedule / replace the periodic WorkManager job (Android minimum = 15 min)
        PeriodicWorkRequest workRequest =
            new PeriodicWorkRequest.Builder(SelfDestructWorker.class, 15, TimeUnit.MINUTES)
                .addTag(WORK_TAG)
                .build();

        WorkManager.getInstance(getApplicationContext())
                   .enqueueUniquePeriodicWork(
                       WORK_TAG,
                       ExistingPeriodicWorkPolicy.REPLACE,
                       workRequest
                   );

        Log.d(TAG, "Self-destruct scheduled — TTL=" + minutes + " min");
        Toast.makeText(this, "Auto-delete set to " + minutes + " minutes", Toast.LENGTH_SHORT).show();
    }

    private void cancelSelfDestruct() {
        WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);
        Log.d(TAG, "Self-destruct cancelled.");
        Toast.makeText(this, "Auto-delete disabled", Toast.LENGTH_SHORT).show();
    }

    private void confirmUnpair() {
        new AlertDialog.Builder(this)
            .setTitle("Unpair Device")
            .setMessage("This will remove all pairing data and delete all local messages. This cannot be undone.")
            .setPositiveButton("Unpair", (dialog, which) -> unpairDevice())
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void unpairDevice() {
        // Cancel any running self-destruct job first
        WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);

        String conversationId = prefs.getString("conversation_id", null);

        // Delete local Room messages on background thread, then clear prefs and navigate
        bgExecutor.execute(() -> {
            try {
                if (conversationId != null) {
                    AppDatabase.getInstance(getApplicationContext())
                               .messageDao()
                               .deleteAll(conversationId);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error deleting messages during unpair", e);
            }

            // Clear ALL shared preferences on main thread
            runOnUiThread(() -> {
                prefs.edit().clear().apply();
                Toast.makeText(this, "Device unpaired.", Toast.LENGTH_SHORT).show();

                Intent intent = new Intent(this, PairingActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!bgExecutor.isShutdown()) bgExecutor.shutdown();
    }
}
