package com.itsluminous.cleartravel

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.notifications.NotificationChannelRegistrar
import com.itsluminous.cleartravel.core.notifications.NotificationPermissions
import com.itsluminous.cleartravel.feature.trains.TrainsSharedTextEntry
import com.itsluminous.cleartravel.startup.AppStartupTasks
import com.itsluminous.cleartravel.ui.ClearTravelApp
import com.itsluminous.cleartravel.ui.JourneysDeepLink
import com.itsluminous.cleartravel.ui.ThemeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-activity Compose shell. `singleTask` in the manifest, so notification deep
 * links and share-sheet sends arrive here — cold via [onCreate]'s intent, warm via
 * [onNewIntent] — instead of stacking duplicate instances.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var channelRegistrar: NotificationChannelRegistrar

    @Inject
    lateinit var startupTasks: AppStartupTasks

    /** Pending notification deep link (ADR-013 contract); cleared once consumed. */
    private val pendingDeepLink = mutableStateOf<JourneysDeepLink?>(null)

    /** Pending ACTION_SEND text (IRCTC SMS/email share); cleared when the form closes. */
    private val pendingSharedText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash (Theme.ClearTravel.Splash): must be installed before
        // super.onCreate() so the handoff to postSplashScreenTheme is seamless.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Idempotent channel creation — every module can post immediately after.
        channelRegistrar.registerAll()

        // Housekeeping off the UI thread: auto-archive past journeys + restart the
        // flight poll chain for existing future flights (work runs on Dispatchers.IO).
        lifecycleScope.launch { startupTasks.runOnAppOpen() }

        consumeIntent(intent)

        val themeViewModel: ThemeViewModel by viewModels()
        setContent {
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            NotificationPermissionEffect()
            ClearTravelTheme(darkTheme = themeMode.resolveDarkTheme()) {
                val sharedText = pendingSharedText.value
                if (sharedText != null) {
                    // Share-sheet entry: the trains add form, prefilled from the
                    // shared text, rendered over the shell until saved/cancelled.
                    Surface {
                        TrainsSharedTextEntry(
                            sharedText = sharedText,
                            onDone = { pendingSharedText.value = null },
                        )
                    }
                } else {
                    ClearTravelApp(
                        journeysDeepLink = pendingDeepLink.value,
                        onJourneysDeepLinkConsumed = { pendingDeepLink.value = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    /** Routes an arriving intent: ACTION_SEND text vs. notification deep link. */
    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        if (intent.action == Intent.ACTION_SEND && intent.type == MIME_TEXT_PLAIN) {
            intent
                .getStringExtra(Intent.EXTRA_TEXT)
                ?.takeIf(String::isNotBlank)
                ?.let { pendingSharedText.value = it }
            return
        }
        JourneysDeepLink.fromIntent(intent)?.let { pendingDeepLink.value = it }
    }

    private companion object {
        const val MIME_TEXT_PLAIN = "text/plain"
    }
}

/** Resolves the persisted preference to the boolean [ClearTravelTheme] expects. */
@Composable
private fun ThemeMode.resolveDarkTheme(): Boolean =
    when (this) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

/**
 * Requests POST_NOTIFICATIONS exactly once per install (Android 13+). Non-nagging by
 * design: a denial is never re-prompted — notification posting silently no-ops via
 * `NotificationPermissions.canPost`, and the user can grant later from Settings.
 */
@Composable
private fun NotificationPermissionEffect() {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        val prefs = context.getSharedPreferences(SHELL_PREFS, Context.MODE_PRIVATE)
        val alreadyAsked = prefs.getBoolean(KEY_NOTIF_PERMISSION_REQUESTED, false)
        if (NotificationPermissions.needsRequest(context) && !alreadyAsked) {
            prefs.edit().putBoolean(KEY_NOTIF_PERMISSION_REQUESTED, true).apply()
            launcher.launch(NotificationPermissions.PERMISSION)
        }
    }
}

private const val SHELL_PREFS = "app_shell_state"
private const val KEY_NOTIF_PERMISSION_REQUESTED = "notification_permission_requested"
