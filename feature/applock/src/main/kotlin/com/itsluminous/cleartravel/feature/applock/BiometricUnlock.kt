package com.itsluminous.cleartravel.feature.applock

import android.content.Context
import android.content.ContextWrapper
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import javax.crypto.Cipher

/**
 * Thin wrapper over `androidx.biometric` (ADR-031). Only BIOMETRIC_STRONG is accepted
 * because the DEK wrap is bound to a Keystore key with `setUserAuthenticationRequired`,
 * which weak biometrics and device credentials cannot satisfy for a `CryptoObject`.
 */
object BiometricUnlock {
    /** Whether the device can run a strong-biometric prompt right now. */
    fun isAvailable(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** The hosting [FragmentActivity] (BiometricPrompt needs one), or null. */
    fun findActivity(context: Context): FragmentActivity? {
        var current: Context? = context
        while (current is ContextWrapper) {
            if (current is FragmentActivity) return current
            current = current.baseContext
        }
        return null
    }

    /**
     * Shows the prompt around [cipher]; [onAuthenticated] receives the authenticated
     * cipher, [onDismissed] fires on cancel/negative button, [onError] on a real
     * failure (lockout, hardware). Returns false when no activity could host it.
     */
    fun prompt(
        context: Context,
        cipher: Cipher,
        title: String,
        negativeButton: String,
        onAuthenticated: (Cipher) -> Unit,
        onDismissed: () -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val activity = findActivity(context) ?: return false
        val callback =
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated != null) onAuthenticated(authenticated) else onError("")
                }

                override fun onAuthenticationError(
                    errorCode: Int,
                    errString: CharSequence,
                ) {
                    when (errorCode) {
                        BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                        BiometricPrompt.ERROR_USER_CANCELED,
                        BiometricPrompt.ERROR_CANCELED,
                        -> onDismissed()
                        else -> onError(errString.toString())
                    }
                }
            }
        val info =
            BiometricPrompt.PromptInfo
                .Builder()
                .setTitle(title)
                .setNegativeButtonText(negativeButton)
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setConfirmationRequired(false)
                .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(context), callback)
            .authenticate(info, BiometricPrompt.CryptoObject(cipher))
        return true
    }
}
