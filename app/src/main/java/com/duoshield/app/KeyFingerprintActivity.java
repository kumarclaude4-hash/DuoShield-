package com.duoshield.app;

import android.content.SharedPreferences;
import android.os.Bundle;
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
        if (toolbar != null) toolbar.setNavigationOnClickListener(v -> finish());

        // Layout IDs: tv_my_fingerprint, tv_partner_fingerprint (no tvStatusLabel in layout)
        TextView tvMyFingerprint      = findViewById(R.id.tv_my_fingerprint);
        TextView tvPartnerFingerprint = findViewById(R.id.tv_partner_fingerprint);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        String myPubKey  = prefs.getString(CryptoInitializer.KEY_EC_PUBLIC, null);
        String sharedKey = prefs.getString(CryptoInitializer.KEY_SHARED_AES, null);

        if (tvMyFingerprint != null)
            tvMyFingerprint.setText(myPubKey != null ? formatFingerprint(sha256(myPubKey)) : "Key not generated yet.");

        if (tvPartnerFingerprint != null)
            tvPartnerFingerprint.setText(sharedKey != null
                ? formatFingerprint(sha256(sharedKey))
                : "Not derived — complete pairing with your partner.");
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

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
        String t = hex.substring(0, 32).toUpperCase();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < t.length(); i += 4) {
            if (i > 0) sb.append(" ");
            sb.append(t.substring(i, Math.min(i + 4, t.length())));
        }
        return sb.toString();
    }
}
