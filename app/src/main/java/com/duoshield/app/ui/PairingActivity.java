package com.duoshield.app.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;
import com.duoshield.app.pairing.PairingManager;
import com.duoshield.app.security.DuressManager;

public class PairingActivity extends BaseActivity {

    private PairingManager pairingManager;

    private Button btnGenerateCode, btnJoinRoom;
    private TextView tvPairCode, tvWaitingStatus, tvPairingError;
    private EditText etJoinCode;
    private ProgressBar progressBar;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        pairingManager = new PairingManager(this);

        btnGenerateCode  = findViewById(R.id.btnGenerateCode);
        btnJoinRoom      = findViewById(R.id.btnJoinRoom);
        tvPairCode       = findViewById(R.id.tvPairCode);
        tvWaitingStatus  = findViewById(R.id.tvWaitingStatus);
        tvPairingError   = findViewById(R.id.tvPairingError);
        etJoinCode       = findViewById(R.id.etJoinCode);
        progressBar      = findViewById(R.id.progressBar);

        btnGenerateCode.setOnClickListener(v -> startCreateRoom());
        btnJoinRoom.setOnClickListener(v -> startJoinRoom());
    }

    // ── Create Room ──────────────────────────────────────────────────────────

    private void startCreateRoom() {
        showLoading(true);
        clearError();

        pairingManager.createRoom(new PairingManager.PairingCallback() {
            @Override
            public void onCodeGenerated(String code) {
                runOnUiThread(() -> {
                    showLoading(false);
                    tvPairCode.setText(code);
                    tvPairCode.setVisibility(View.VISIBLE);
                    // Disable both buttons while waiting
                    btnGenerateCode.setEnabled(false);
                    btnJoinRoom.setEnabled(false);
                    etJoinCode.setEnabled(false);
                });
            }

            @Override
            public void onWaitingForPartner() {
                runOnUiThread(() -> tvWaitingStatus.setVisibility(View.VISIBLE));
            }

            @Override
            public void onPaired() {
                runOnUiThread(() -> goToChat());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    showError(message);
                    resetButtons();
                });
            }
        });
    }

    // ── Join Room ────────────────────────────────────────────────────────────

    private void startJoinRoom() {
        String code = etJoinCode.getText().toString().trim();
        if (code.length() != 6) {
            showError("Please enter the full 6-digit code.");
            return;
        }

        showLoading(true);
        clearError();
        btnGenerateCode.setEnabled(false);
        btnJoinRoom.setEnabled(false);

        // ── Duress PIN check — silently wipe if entered ───────────────────────
        if (DuressManager.isDuressPin(this, code)) {
            DuressManager.triggerDuress(this);
            return;
        }

        pairingManager.joinRoom(code, new PairingManager.PairingCallback() {
            @Override
            public void onCodeGenerated(String code) { /* not used for joiner */ }

            @Override
            public void onWaitingForPartner() { /* not used for joiner */ }

            @Override
            public void onPaired() {
                runOnUiThread(() -> goToChat());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    showError(message);
                    resetButtons();
                });
            }
        });
    }

    // ── Navigation ───────────────────────────────────────────────────────────

    private void goToChat() {
        Intent intent = new Intent(this, ChatMediaActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    // ── UI helpers ───────────────────────────────────────────────────────────

    private void showLoading(boolean show) {
        progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showError(String message) {
        tvPairingError.setText(message);
        tvPairingError.setVisibility(View.VISIBLE);
    }

    private void clearError() {
        tvPairingError.setVisibility(View.GONE);
    }

    private void resetButtons() {
        btnGenerateCode.setEnabled(true);
        btnJoinRoom.setEnabled(true);
        etJoinCode.setEnabled(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Cancel room listener if user backs out while waiting
        if (pairingManager != null) pairingManager.stopListening();
    }
}
