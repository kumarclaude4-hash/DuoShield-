package com.duoshield.app.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.Toolbar;
import com.duoshield.app.BaseActivity;
import com.duoshield.app.ChatMediaActivity;
import com.duoshield.app.R;
import com.duoshield.app.pairing.PairingManager;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.journeyapps.barcodescanner.BarcodeEncoder;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanIntentResult;
import com.journeyapps.barcodescanner.ScanOptions;

/**
 * "Connect" screen.
 *
 * QR flow (one-sided — only the scanner needs to act):
 *  • User A taps "Show My QR Code"  → dialog shows a QR of their DS-XXXXXXXX ID.
 *  • User B taps "Scan Partner's QR" → camera opens, scans User A's code,
 *    populates the partner-ID field and auto-calls connectByUserId().
 *  User A does NOT need to scan anything; the connection is symmetric once
 *  User B completes it (both devices derive the same chatId and ECDH key).
 *
 * Layout re-uses existing activity_pairing.xml view IDs:
 *   tvMyCode       → own DS-XXXXXXXX User ID
 *   btnCopyCode    → copies own ID
 *   btnShowQr      → NEW: shows QR dialog
 *   etPartnerCode  → partner ID input
 *   btnScanQr      → NEW: launches camera scanner
 *   btnPair        → manual "Connect" button
 *   progressPairing→ spinner
 */
public class PairingActivity extends BaseActivity {

    private PairingManager pairingManager;
    private String         myUserId;

    private TextView    tvMyUserId;
    private EditText    etPartnerUserId;
    private Button      btnCopyMyId;
    private Button      btnShowQr;
    private Button      btnScanQr;
    private Button      btnConnect;
    private ProgressBar progressPairing;

    // ── Scan launcher ─────────────────────────────────────────────────────────
    // Must be registered before onCreate completes (field initializer is safe).
    private final ActivityResultLauncher<ScanOptions> scanLauncher =
            registerForActivityResult(new ScanContract(), this::onScanResult);

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

        tvMyUserId      = findViewById(R.id.tvMyCode);
        etPartnerUserId = findViewById(R.id.etPartnerCode);
        btnCopyMyId     = findViewById(R.id.btnCopyCode);
        btnShowQr       = findViewById(R.id.btnShowQr);
        btnScanQr       = findViewById(R.id.btnScanQr);
        btnConnect      = findViewById(R.id.btnPair);
        progressPairing = findViewById(R.id.progressPairing);

        SharedPreferences prefs = getSharedPreferences("duoshield_prefs", MODE_PRIVATE);
        myUserId = prefs.getString("my_user_id", null);

        if (tvMyUserId != null) {
            tvMyUserId.setText(myUserId != null ? myUserId : "—");
        }

        if (btnCopyMyId != null) {
            btnCopyMyId.setOnClickListener(v -> copyMyId());
        }

        if (btnShowQr != null) {
            btnShowQr.setOnClickListener(v -> showQrDialog());
        }

        if (btnScanQr != null) {
            btnScanQr.setOnClickListener(v -> launchScanner());
        }

        if (btnConnect != null) {
            btnConnect.setOnClickListener(v -> {
                String partnerId = readPartnerInput();
                if (partnerId.isEmpty()) {
                    Toast.makeText(this,
                            "Enter your partner's User ID or scan their QR code.",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                startConnect(partnerId);
            });
        }
    }

    // ── Copy own ID ───────────────────────────────────────────────────────────

    private void copyMyId() {
        if (myUserId == null || myUserId.isEmpty()) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("user_id", myUserId));
        Toast.makeText(this, "User ID copied!", Toast.LENGTH_SHORT).show();
    }

    // ── Show QR dialog ────────────────────────────────────────────────────────

    private void showQrDialog() {
        if (myUserId == null || myUserId.isEmpty() || "—".equals(myUserId)) {
            Toast.makeText(this, "Your User ID isn't ready yet. Restart the app.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            BarcodeEncoder encoder = new BarcodeEncoder();
            Bitmap bitmap = encoder.encodeBitmap(myUserId, BarcodeFormat.QR_CODE, 600, 600);

            // Build dialog view
            int dp16 = dp(16);
            int dp8  = dp(8);

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setPadding(dp16, dp8, dp16, dp8);

            TextView subtitle = new TextView(this);
            subtitle.setText("Ask your partner to scan this. They don't need to show you theirs.");
            subtitle.setTextSize(13f);
            subtitle.setTextColor(0xFFAAAAAA);
            subtitle.setPadding(0, 0, 0, dp16);
            container.addView(subtitle);

            ImageView qrView = new ImageView(this);
            qrView.setImageBitmap(bitmap);
            qrView.setBackgroundColor(Color.WHITE);
            int size = dp(260);
            LinearLayout.LayoutParams lp =
                    new LinearLayout.LayoutParams(size, size);
            lp.setMargins(0, 0, 0, dp8);
            qrView.setLayoutParams(lp);
            container.addView(qrView);

            TextView idLabel = new TextView(this);
            idLabel.setText(myUserId);
            idLabel.setTextSize(16f);
            idLabel.setTypeface(android.graphics.Typeface.MONOSPACE);
            idLabel.setTextColor(0xFFFFFFFF);
            idLabel.setPadding(0, dp8, 0, 0);
            container.addView(idLabel);

            new AlertDialog.Builder(this)
                    .setTitle("Your QR Code")
                    .setView(container)
                    .setPositiveButton("Done", null)
                    .setNeutralButton("Copy ID", (d, w) -> copyMyId())
                    .show();

        } catch (WriterException e) {
            Toast.makeText(this, "Could not generate QR code.", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Launch camera scanner ─────────────────────────────────────────────────

    private void launchScanner() {
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Scan your partner's DuoShield QR code");
        options.setBeepEnabled(false);
        options.setBarcodeImageEnabled(false);
        options.setOrientationLocked(false);
        scanLauncher.launch(options);
    }

    private void onScanResult(ScanIntentResult result) {
        if (result == null || result.getContents() == null) return;
        String scanned = result.getContents().trim().toUpperCase();
        if (!scanned.matches("DS-[0-9A-F]{8}")) {
            Toast.makeText(this,
                    "Invalid QR code. Make sure you're scanning a DuoShield QR.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Populate the text field so the user can see what was scanned
        if (etPartnerUserId != null) etPartnerUserId.setText(scanned);
        // Auto-connect immediately
        startConnect(scanned);
    }

    // ── Manual connect flow ───────────────────────────────────────────────────

    private String readPartnerInput() {
        if (etPartnerUserId == null || etPartnerUserId.getText() == null) return "";
        return etPartnerUserId.getText().toString().trim();
    }

    private void startConnect(String partnerId) {
        showLoading(true);
        setInputsEnabled(false);

        pairingManager.connectByUserId(partnerId, new PairingManager.PairingCallback() {
            @Override
            public void onPaired() {
                runOnUiThread(() -> goToChat());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    showLoading(false);
                    setInputsEnabled(true);
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

    private void setInputsEnabled(boolean enabled) {
        if (btnConnect  != null) btnConnect.setEnabled(enabled);
        if (btnScanQr   != null) btnScanQr.setEnabled(enabled);
        if (btnShowQr   != null) btnShowQr.setEnabled(enabled);
        if (btnCopyMyId != null) btnCopyMyId.setEnabled(enabled);
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
