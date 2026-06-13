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

/**
 * "Connect" screen — replaces the old 6-digit-code pairing screen.
 *
 * User A shares their DS-XXXXXXXX User ID out-of-band (copy/paste, QR, etc.).
 * User B enters that ID here → connectByUserId() handles ECDH + chatId derivation.
 *
 * Layout reuses activity_pairing.xml view IDs for backwards compatibility:
 *   tvMyCode       → shows own User ID
 *   btnCopyCode    → copies own User ID
 *   etPartnerCode  → input field for partner's User ID
 *   btnPair        → "Connect" button
 *   progressPairing→ spinner while connecting
 */
public class PairingActivity extends BaseActivity {

    private PairingManager pairingManager;
    private TextView    tvMyUserId;
    private EditText    etPartnerUserId;
    private Button      btnCopyMyId;
    private Button      btnConnect;
    private ProgressBar progressPairing;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pairing);

        Toolbar toolbar = findViewById(R.id.toolbar);
        if (toolbar != null) {
            setSupportActionBar(toolbar);
            if (getSupportActionBar() != null)
                getSupportActionBar().setTitle("Connect");
        }

        pairingManager  = new PairingManager(this);

        // Re-use existing layout view IDs so no XML change is required
        tvMyUserId      = findViewById(R.id.tvMyCode);
        etPartnerUserId = findViewById(R.id.etPartnerCode);
        btnCopyMyId     = findViewById(R.id.btnCopyCode);
        btnConnect      = findViewById(R.id.btnPair);
        progressPairing = findViewById(R.id.progressPairing);

        // Show own User ID (DS-XXXXXXXX) from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String myUserId = prefs.getString("my_user_id", null);
        if (tvMyUserId != null) {
            if (myUserId != null && !myUserId.isEmpty()) {
                tvMyUserId.setText(myUserId);
                tvMyUserId.setVisibility(View.VISIBLE);
            } else {
                tvMyUserId.setText("—");
                tvMyUserId.setVisibility(View.VISIBLE);
            }
        }

        // Copy-to-clipboard button
        if (btnCopyMyId != null) {
            btnCopyMyId.setOnClickListener(v -> {
                if (tvMyUserId == null) return;
                String id = tvMyUserId.getText().toString().trim();
                if (id.isEmpty() || "—".equals(id)) return;
                ClipboardManager cm =
                        (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("user_id", id));
                Toast.makeText(this, "User ID copied!", Toast.LENGTH_SHORT).show();
            });
        }

        // Connect button
        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                if (etPartnerUserId == null) return;
                String partnerId = etPartnerUserId.getText() != null
                        ? etPartnerUserId.getText().toString().trim() : "";
                if (partnerId.isEmpty()) {
                    Toast.makeText(this,
                            "Enter your partner's User ID (DS-XXXXXXXX).",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                startConnect(partnerId);
            });
        }
    }

    private void startConnect(String partnerId) {
        showLoading(true);
        if (btnConnect != null) btnConnect.setEnabled(false);

        pairingManager.connectByUserId(partnerId, new PairingManager.PairingCallback() {
            @Override
            public void onPaired() {
                runOnUiThread(() -> goToChat());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    if (btnConnect != null) btnConnect.setEnabled(true);
                    Toast.makeText(PairingActivity.this, message, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void goToChat() {
        getSharedPreferences("duoshield_prefs", MODE_PRIVATE)
                .edit().putBoolean("is_paired", true).apply();
        startActivity(new Intent(this, ChatMediaActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void showLoading(boolean show) {
        if (progressPairing != null)
            progressPairing.setVisibility(show ? View.VISIBLE : View.GONE);
    }
}
