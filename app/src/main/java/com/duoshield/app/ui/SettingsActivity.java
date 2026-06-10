package com.duoshield.app.ui;

  import android.content.Intent;
  import android.content.SharedPreferences;
  import android.os.Bundle;
  import android.util.Log;
  import android.view.View;
  import android.widget.Button;
  import android.widget.EditText;
  import android.widget.LinearLayout;
  import android.widget.TextView;
  import android.widget.Toast;
  import androidx.appcompat.app.AlertDialog;
  import androidx.appcompat.app.AppCompatActivity;
  import androidx.appcompat.app.AppCompatDelegate;
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

      private static final String TAG             = "SettingsActivity";
      private static final String WORK_TAG        = "self_destruct_work";
      private static final long   DEFAULT_TTL_MIN = 60L;
      private static final long   MIN_TTL         = 5L;
      private static final long   MAX_TTL         = 10_080L;

      // F4: disappearing TTL options (ms), 0 = off
      private static final long[] DISAPPEAR_MS    = {0, 60_000L, 300_000L, 3_600_000L, 86_400_000L};
      private static final String[] DISAPPEAR_LBL = {"Off", "1 minute", "5 minutes", "1 hour", "1 day"};

      private SharedPreferences prefs;
      private SwitchCompat      switchNotifications, switchSelfDestruct, switchBiometric, switchDarkMode;
      private LinearLayout      layoutMinutes;
      private EditText          editTtlMinutes;
      private EditText          etDuressPin;
      private TextView          textTtlHint;
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

          switchNotifications = findViewById(R.id.switchNotifications);
          switchSelfDestruct  = findViewById(R.id.switchSelfDestruct);
          switchBiometric     = findViewById(R.id.switchBiometric);
          switchDarkMode      = findViewById(R.id.switchDarkMode);
          layoutMinutes       = findViewById(R.id.layoutMinutes);
          editTtlMinutes      = findViewById(R.id.editTtlMinutes);
          textTtlHint         = findViewById(R.id.textTtlHint);
          etDuressPin         = findViewById(R.id.etDuressPin);
          btnDisappearing     = findViewById(R.id.btnDisappearing);
          Button btnSetDuressPin = findViewById(R.id.btnSetDuressPin);
          Button btnUnpair       = findViewById(R.id.btnUnpair);

          // ── Restore saved states ─────────────────────────────────────────────
          switchNotifications.setChecked(prefs.getBoolean("notifications_enabled", true));
          boolean destructOn = prefs.getBoolean("self_destruct_enabled", false);
          switchSelfDestruct.setChecked(destructOn);
          editTtlMinutes.setText(String.valueOf(prefs.getLong("self_destruct_minutes", DEFAULT_TTL_MIN)));
          setMinutesVisible(destructOn);
          switchBiometric.setChecked(prefs.getBoolean("biometric_enabled", false));

          // F2: Dark mode
          boolean dark = prefs.getBoolean("dark_mode", false);
          switchDarkMode.setChecked(dark);

          // F4: Disappearing label
          updateDisappearLabel();

          // ── Listeners ─────────────────────────────────────────────────────
          switchNotifications.setOnCheckedChangeListener((b, c) ->
              prefs.edit().putBoolean("notifications_enabled", c).apply());

          // F2: Dark mode toggle
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

          switchBiometric.setOnCheckedChangeListener((b, c) ->
              prefs.edit().putBoolean("biometric_enabled", c).apply());

          // F4: Disappearing messages picker
          btnDisappearing.setOnClickListener(v -> showDisappearingPicker());

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

          btnUnpair.setOnClickListener(v -> confirmUnpair());
      }

      // F4: show dialog to pick disappearing message TTL
      private void showDisappearingPicker() {
          new AlertDialog.Builder(this)
              .setTitle("Disappearing messages")
              .setItems(DISAPPEAR_LBL, (d, which) -> {
                  prefs.edit().putLong("disappear_ms", DISAPPEAR_MS[which]).apply();
                  updateDisappearLabel();
                  Toast.makeText(this,
                      which == 0 ? "Disappearing messages off" : "Set to " + DISAPPEAR_LBL[which],
                      Toast.LENGTH_SHORT).show();
              })
              .show();
      }

      private void updateDisappearLabel() {
          if (btnDisappearing == null) return;
          long ttl = prefs.getLong("disappear_ms", 0);
          String label = "Off";
          for (int i = 0; i < DISAPPEAR_MS.length; i++) {
              if (DISAPPEAR_MS[i] == ttl) { label = DISAPPEAR_LBL[i]; break; }
          }
          btnDisappearing.setText(label);
      }

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
              } catch (Exception e) { Log.e(TAG, "Error deleting messages during unpair", e); }
              runOnUiThread(() -> {
                  prefs.edit().clear().apply();
                  Toast.makeText(this, "Device unpaired.", Toast.LENGTH_SHORT).show();
                  Intent i = new Intent(this, PairingActivity.class);
                  i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                  startActivity(i); finish();
              });
          });
      }

      @Override protected void onDestroy() { super.onDestroy(); if (!bgExecutor.isShutdown()) bgExecutor.shutdown(); }
  }