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
import com.duoshield.app.util.AppLockManager;
import com.duoshield.app.util.HapticHelper;
import com.duoshield.app.util.PinManager;
import com.duoshield.app.util.WipeHelper;

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

        etPin       = findViewById(R.id.etPin);
        tvError     = findViewById(R.id.tvError);
        btnUnlock   = findViewById(R.id.btnUnlock);
        btnBiometric = findViewById(R.id.btnBiometric);

        btnUnlock.setOnClickListener(v -> checkPin());
        btnBiometric.setOnClickListener(v -> showBiometric());

        showBiometric();
    }

    private void showBiometric() {
        BiometricHelper.authenticate(this, new BiometricHelper.AuthCallback() {
            @Override public void onSuccess() { unlock(); }
            @Override public void onFailure() {}
        });
    }

    private void checkPin() {
        String entered = etPin.getText().toString().trim();
        if (entered.isEmpty()) return;

        if (com.duoshield.app.security.DuressManager.isDuressPin(this, entered)) {
            com.duoshield.app.security.DuressManager.triggerDuress(this);
            return;
        }

        if (PinManager.verifyPin(this, entered)) {
            unlock();
        } else {
            HapticHelper.wrongPin(this);
            tvError.setText("Wrong PIN. Try again.");
            tvError.setVisibility(View.VISIBLE);
            etPin.setText("");
        }
    }

    private void unlock() {
        AppLockManager.onAppForegrounded(this);
        finish();
    }

    @Override public void onBackPressed() {}
}
