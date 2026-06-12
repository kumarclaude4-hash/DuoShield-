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

    public interface AuthCallback {
        void onSuccess();
        void onFailure(); // biometric unavailable or user chose "Use PIN"
    }

    /**
     * Shows a biometric-ONLY prompt (fingerprint / face).
     * Does NOT fall back to the device/phone PIN — DuoShield has its own app PIN for that.
     * If the user taps "Use PIN" or biometric is unavailable, onFailure() is called
     * so the caller can show the in-app PIN entry screen instead.
     */
    public static void authenticate(FragmentActivity activity, AuthCallback callback) {
        BiometricManager bm = BiometricManager.from(activity);
        int canAuth = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);

        if (canAuth == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
                || canAuth == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                || canAuth == BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE) {
            // No biometric hardware or nothing enrolled — let the app PIN handle it
            callback.onFailure();
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
                        // User tapped "Use PIN" or dismissed — fall back to app PIN
                        callback.onFailure();
                    }

                    @Override
                    public void onAuthenticationFailed() {
                        // Single bad attempt — BiometricPrompt shows retry UI automatically
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("DuoShield")
                .setSubtitle("Use fingerprint or face to unlock")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Use PIN instead")
                .build();

        prompt.authenticate(promptInfo);
    }

    /** Returns true if the device has enrolled biometrics that can authenticate. */
    public static boolean isAvailable(Context context) {
        int canAuth = BiometricManager.from(context)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG);
        return canAuth == BiometricManager.BIOMETRIC_SUCCESS;
    }
}
