package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;
import com.duoshield.app.pairing.PairingManager;
import com.duoshield.app.security.DuressManager;

public class PairingActivity extends BaseActivity {

    private PairingManager pairingManager;
    private TextView    tvMyCode;
    private Button      btnCopyCode, btnPair;
    private EditText    etPartnerCode;
    private ProgressBar progressPairing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("Pair Device");
        }

        pairingManager  = new PairingManager(this);
        tvMyCode        = findViewById(R.id.tvMyCode);
        btnCopyCode     = findViewById(R.id.btnCopyCode);
        etPartnerCode   = findViewById(R.id.etPartnerCode);
        btnPair         = findViewById(R.id.btnPair);
        progressPairing = findViewById(R.id.progressPairing);

        startCreateRoom();

        if (btnCopyCode != null) {
            btnCopyCode.setOnClickListener(v -> {
                if (tvMyCode == null) return;
                String code = tvMyCode.getText().toString().trim();
                if (code.isEmpty()) return;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("pair_code", code));
                Toast.makeText(this, "Code copied!", Toast.LENGTH_SHORT).show();
            });
        }

        if (btnPair != null) {
            btnPair.setOnClickListener(v -> {
                if (etPartnerCode == null) return;
                String code = etPartnerCode.getText().toString().trim();
                if (code.length() != 6) {
                    Toast.makeText(this, "Enter the full 6-digit code.", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (DuressManager.isDuressPin(this, code)) { DuressManager.triggerDuress(this); return; }
                startJoinRoom(code);
            });
        }
    }

    private void startCreateRoom() {
        showLoading(true);
        pairingManager.createRoom(new PairingManager.PairingCallback() {
            @Override public void onCodeGenerated(String code) {
                runOnUiThread(() -> {
                    showLoading(false);
                    if (tvMyCode != null) { tvMyCode.setText(code); tvMyCode.setVisibility(View.VISIBLE); }
                });
            }
            @Override public void onWaitingForPartner() {}
            @Override public void onPaired() { runOnUiThread(() -> goToChat()); }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    Toast.makeText(PairingActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void startJoinRoom(String code) {
        showLoading(true);
        if (btnPair != null) btnPair.setEnabled(false);
        pairingManager.joinRoom(code, new PairingManager.PairingCallback() {
            @Override public void onCodeGenerated(String c) {}
            @Override public void onWaitingForPartner() {}
            @Override public void onPaired() { runOnUiThread(() -> goToChat()); }
            @Override public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    if (btnPair != null) btnPair.setEnabled(true);
                    Toast.makeText(PairingActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void goToChat() {
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        prefs.edit().putBoolean("is_paired", true).apply();
        startActivity(new Intent(this, ChatMediaActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void showLoading(boolean show) {
        if (progressPairing != null) progressPairing.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
