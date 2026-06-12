package com.duoshield.app.ui;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import androidx.appcompat.widget.Toolbar;
import androidx.work.WorkManager;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.R;
import com.duoshield.app.db.AppDatabase;
import com.duoshield.app.security.BiometricHelper;
import com.duoshield.app.security.DuressManager;
import com.duoshield.app.util.PinManager;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SettingsActivity extends BaseActivity {

    private static final String WORK_TAG = "self_destruct_work";

    private SharedPreferences prefs;
    private SwitchCompat      switchBiometric;
    private EditText          etDuressPin, etNewPin, etConfirmPin;
    private TextView          tvPinStatus;
    private final ExecutorService bgExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Layout uses id="toolbar", NOT "settingsToolbar"
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);

        switchBiometric = findViewById(R.id.switchBiometric);
        etDuressPin     = findViewById(R.id.etDuressPin);
        etNewPin        = findViewById(R.id.etNewPin);
        etConfirmPin    = findViewById(R.id.etConfirmPin);
        tvPinStatus     = findViewById(R.id.tvPinStatus);

        Button btnSetPin       = findViewById(R.id.btnSetPin);
        Button btnClearPin     = findViewById(R.id.btnClearPin);
        Button btnSetDuressPin = findViewById(R.id.btnSetDuressPin);

        if (switchBiometric != null)
            switchBiometric.setChecked(prefs.getBoolean("biometric_enabled", false));
        refreshPinStatus();

        if (btnSetPin       != null) btnSetPin.setOnClickListener(v -> saveAppPin());
        if (btnClearPin     != null) btnClearPin.setOnClickListener(v -> confirmClearPin());
        if (btnSetDuressPin != null) btnSetDuressPin.setOnClickListener(v -> saveDuressPin());

        if (switchBiometric != null) {
            switchBiometric.setOnCheckedChangeListener((b, checked) -> {
                if (checked && !PinManager.hasPinSet(this)) {
                    switchBiometric.setChecked(false);
                    Toast.makeText(this, "Set an app PIN first.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (checked && !BiometricHelper.isAvailable(this)) {
                    switchBiometric.setChecked(false);
                    Toast.makeText(this, "No biometric enrolled on this device.", Toast.LENGTH_LONG).show();
                    return;
                }
                prefs.edit().putBoolean("biometric_enabled", checked).apply();
            });
        }
    }

    private void saveAppPin() {
        if (etNewPin == null || etConfirmPin == null) return;
        String pin = etNewPin.getText().toString().trim();
        String confirm = etConfirmPin.getText().toString().trim();
        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "PIN must be 4-6 digits.", Toast.LENGTH_SHORT).show(); return;
        }
        if (!pin.equals(confirm)) {
            Toast.makeText(this, "PINs don't match.", Toast.LENGTH_SHORT).show();
            etConfirmPin.setText(""); return;
        }
        if (DuressManager.isDuressPin(this, pin)) {
            Toast.makeText(this, "App PIN cannot match duress PIN.", Toast.LENGTH_LONG).show(); return;
        }
        PinManager.setPin(this, pin);
        etNewPin.setText(""); etConfirmPin.setText("");
        refreshPinStatus();
        Toast.makeText(this, "App PIN set.", Toast.LENGTH_SHORT).show();
    }

    private void confirmClearPin() {
        if (!PinManager.hasPinSet(this)) {
            Toast.makeText(this, "No app PIN set.", Toast.LENGTH_SHORT).show(); return;
        }
        new AlertDialog.Builder(this)
            .setTitle("Clear App PIN")
            .setMessage("Remove PIN? Lock screen will be disabled.")
            .setPositiveButton("Clear", (d, w) -> {
                PinManager.clearPin(this);
                prefs.edit().putBoolean("biometric_enabled", false).apply();
                if (switchBiometric != null) switchBiometric.setChecked(false);
                refreshPinStatus();
                Toast.makeText(this, "App PIN cleared.", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null).show();
    }

    private void refreshPinStatus() {
        if (tvPinStatus == null) return;
        tvPinStatus.setText(PinManager.hasPinSet(this) ? "PIN is set" : "No PIN set");
        tvPinStatus.setTextColor(getResources().getColor(
            PinManager.hasPinSet(this) ? R.color.ds_online : R.color.text_hint, null));
    }

    private void saveDuressPin() {
        if (etDuressPin == null) return;
        String pin = etDuressPin.getText().toString().trim();
        if (pin.length() < 4 || pin.length() > 6) {
            Toast.makeText(this, "Duress PIN must be 4-6 digits.", Toast.LENGTH_SHORT).show(); return;
        }
        if (PinManager.verifyPin(this, pin)) {
            Toast.makeText(this, "Duress PIN cannot match app PIN.", Toast.LENGTH_LONG).show(); return;
        }
        DuressManager.setDuressPin(this, pin);
        etDuressPin.setText("");
        Toast.makeText(this, "Duress PIN set.", Toast.LENGTH_SHORT).show();
    }

    private void unpairDevice() {
        WorkManager.getInstance(getApplicationContext()).cancelAllWorkByTag(WORK_TAG);
        String convId = prefs.getString("conversation_id", null);
        bgExecutor.execute(() -> {
            try {
                if (convId != null)
                    AppDatabase.getInstance(getApplicationContext()).messageDao().deleteAll(convId);
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                prefs.edit().clear().apply();
                Intent intent = new Intent(this, PairingActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        });
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }
    @Override protected void onDestroy() { super.onDestroy(); bgExecutor.shutdown(); }
}
