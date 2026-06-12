package com.duoshield.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.TextView;
import com.duoshield.app.crypto.CryptoInitializer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class KeyFingerprintActivity extends BaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_key_fingerprint);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Key Fingerprint");
        }
        toolbar.setNavigationOnClickListener(v -> finish());

        TextView tvMyFingerprint      = findViewById(R.id.tvMyFingerprint);
        TextView tvSharedFingerprint  = findViewById(R.id.tvSharedFingerprint);
        TextView tvStatusLabel        = findViewById(R.id.tvStatusLabel);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String myPubKey    = prefs.getString(CryptoInitializer.KEY_EC_PUBLIC, null);
        String sharedKey   = prefs.getString(CryptoInitializer.KEY_SHARED_AES, null);

        if (myPubKey != null) {
            tvMyFingerprint.setText(formatFingerprint(sha256(myPubKey)));
        } else {
            tvMyFingerprint.setText("Key not generated yet.");
        }

        if (sharedKey != null) {
            tvSharedFingerprint.setText(formatFingerprint(sha256(sharedKey)));
            tvStatusLabel.setText("✓ E2E encryption active");
        } else {
            tvSharedFingerprint.setText("Not yet derived – complete pairing with your partner.");
            tvStatusLabel.setText("⚠ Pairing incomplete");
        }
    }

    private String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) { return "error"; }
    }

    private String formatFingerprint(String hex) {
        if (hex == null || hex.length() < 32) return hex;
        String trimmed = hex.substring(0, 32).toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < trimmed.length(); i += 4) {
            if (i > 0) sb.append(" ");
            sb.append(trimmed.substring(i, Math.min(i + 4, trimmed.length())));
        }
        return sb.toString();
    }
}
