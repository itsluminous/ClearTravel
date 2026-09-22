package com.itsluminous.cleartravel.feature.applock

import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.itsluminous.cleartravel.core.security.biometric.BiometricUnlock

/**
 * Keeps the hosting activity's window `FLAG_SECURE` in step with [secure] (ADR-031
 * follow-up, `LockTiming.securesWindow`): while set, the Recents task snapshot is
 * blank and screenshots / screen recording of the app are refused. Cleared again when
 * the setting changes or the gate leaves composition, so a relaxed timing restores the
 * normal window without a restart.
 */
@Composable
internal fun SecureWindowEffect(secure: Boolean) {
    val context = LocalContext.current
    DisposableEffect(secure, context) {
        val window = BiometricUnlock.findActivity(context)?.window
        if (window != null) {
            if (secure) {
                window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
