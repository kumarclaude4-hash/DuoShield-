package com.duoshield.app.security;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import java.util.concurrent.Executor;

public class BiometricHelper {

    // ── Callback interface ────────────────────────────────────────────────────

    public interface AuthCallback {
        void onSuccess();
        void onFailure();
    }

    // ── Authenticate ──────────────────────────────────────────────────────────

    /**
     * Shows a biometric prompt for the given activity.
     *
     * Allowed authenticators: BIOMETRIC_STRONG | DEVICE_CREDENTIAL
     * This means the user can also use their PIN/pattern/password as fallback.
     *
     * If no biometric or device credential is enrolled, or the hardware is absent,
     * onSuccess is called immediately so the app doesn't lock the user out.
     *
     * API level note: DEVICE_CREDENTIAL as a standalone authenticator requires API 30+.
     * For API 28-29 we use BIOMETRIC_WEAK | DEVICE_CREDENTIAL (covers enrolled biometrics
     * and falls back gracefully). minSdk is 26, so we handle the range explicitly.
     */
    public static void authenticate(FragmentActivity activity, AuthCallback callback) {
        int allowedAuthenticators = resolveAuthenticators();

        // Check hardware/enrollment status before showing the prompt
        BiometricManager biometricManager = BiometricManager.from(activity);
        int canAuth = biometricManager.canAuthenticate(allowedAuthenticators);

        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                || canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                || canAuth == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
            // No biometric capability — skip the gate, proceed directly to chat
            callback.onSuccess();
            return;
        }

        Executor executor = ContextCompat.getMainExecutor(activity);

        BiometricPrompt prompt = new BiometricPrompt(activity, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(
                            @NonNull BiometricPrompt.AuthenticationResult result) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode,
                                                      @NonNull CharSequence errString) {
                        // User cancelled or too many attempts — send them back
                        callback.onFailure();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Single failed attempt — BiometricPrompt handles retry UI;
                        // we only act on final error/success callbacks.
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("DuoShield")
                .setSubtitle("Verify your identity to access messages")
                .setAllowedAuthenticators(allowedAuthenticators)
                .build();
        // Note: setNegativeButtonText is mutually exclusive with DEVICE_CREDENTIAL.
        // When DEVICE_CREDENTIAL is included the system provides its own cancel flow.

        prompt.authenticate(promptInfo);
    }

    // ── Pick authenticators by API level ─────────────────────────────────────

    private static int resolveAuthenticators() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // API 30+: BIOMETRIC_STRONG | DEVICE_CREDENTIAL is fully supported
            return BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        } else {
            // API 26-29: use BIOMETRIC_WEAK | DEVICE_CREDENTIAL for broader compat
            return BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
        }
    }
}
