package com.itsluminous.cleartravel

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.notifications.NotificationChannelRegistrar
import com.itsluminous.cleartravel.core.notifications.NotificationPermissions
import com.itsluminous.cleartravel.feature.flights.FlightsEntryRequest
import com.itsluminous.cleartravel.feature.flights.FlightsExternalEntry
import com.itsluminous.cleartravel.feature.trains.TrainsEntryRequest
import com.itsluminous.cleartravel.feature.trains.TrainsExternalEntry
import com.itsluminous.cleartravel.feature.trains.share.TicketShareLinks
import com.itsluminous.cleartravel.startup.AppStartupTasks
import com.itsluminous.cleartravel.ui.ClearTravelApp
import com.itsluminous.cleartravel.ui.JourneysDeepLink
import com.itsluminous.cleartravel.ui.ThemeViewModel
import com.itsluminous.cleartravel.ui.intake.IntakeRoute
import com.itsluminous.cleartravel.ui.intake.SharedFileIntakeDialog
import com.itsluminous.cleartravel.ui.intake.SharedFileIntakeViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Single-activity Compose shell. `singleTask` in the manifest, so notification deep
 * links, PNR share links and share-sheet sends arrive here — cold via [onCreate]'s
 * intent, warm via [onNewIntent] — instead of stacking duplicate instances.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var channelRegistrar: NotificationChannelRegistrar

    @Inject
    lateinit var startupTasks: AppStartupTasks

    /** Pending notification deep link (ADR-013 contract); cleared once consumed. */
    private val pendingDeepLink = mutableStateOf<JourneysDeepLink?>(null)

    /**
     * Pending external entry into a feature form — shared IRCTC text or a confirmed
     * shared file for trains, a PNR share link (ADR-020), or a confirmed shared file
     * for flights. Cleared when the hosted form closes.
     */
    private val pendingEntry = mutableStateOf<ExternalEntry?>(null)

    /** A shared image/PDF awaiting the "What's this file?" intake dialog. */
    private val pendingSharedFile = mutableStateOf<Uri?>(null)

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
        val intakeViewModel: SharedFileIntakeViewModel by viewModels()
        setContent {
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            NotificationPermissionEffect()
            ClearTravelTheme(darkTheme = themeMode.resolveDarkTheme()) {
                when (val entry = pendingEntry.value) {
                    // External entry: a feature's add form rendered over the shell
                    // until saved/cancelled; keyed by nonce so a repeated request
                    // re-creates (and re-prefills) the form.
                    // When the hosted form finishes, the shell lands on Journeys with
                    // the matching segment — showing what was just added (ADR-024)
                    // instead of the default Trips tab.
                    is ExternalEntry.Trains ->
                        key(entry.nonce) {
                            Surface {
                                TrainsExternalEntry(
                                    request = entry.request,
                                    onDone = { result ->
                                        pendingDeepLink.value = JourneysDeepLink.forTrainsEntry(result)
                                        pendingEntry.value = null
                                    },
                                )
                            }
                        }
                    is ExternalEntry.Flights ->
                        key(entry.nonce) {
                            Surface {
                                FlightsExternalEntry(
                                    request = entry.request,
                                    onDone = { savedFlightId ->
                                        pendingDeepLink.value = JourneysDeepLink.forFlightsEntry(savedFlightId)
                                        pendingEntry.value = null
                                    },
                                )
                            }
                        }
                    null ->
                        ClearTravelApp(
                            journeysDeepLink = pendingDeepLink.value,
                            onJourneysDeepLinkConsumed = { pendingDeepLink.value = null },
                        )
                }
                SharedFileIntakeHost(
                    viewModel = intakeViewModel,
                    sharedFile = pendingSharedFile.value,
                    onRouted = { route ->
                        pendingSharedFile.value = null
                        pendingEntry.value = route.toExternalEntry()
                    },
                    onCancelled = { pendingSharedFile.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    /**
     * Routes an arriving intent: ACTION_SEND text (train SMS → form directly),
     * ACTION_SEND image/PDF (→ intake dialog), ACTION_VIEW PNR link (→ train form
     * carrying the PNR), else a notification deep link.
     */
    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == MIME_TEXT_PLAIN) {
                    intent
                        .getStringExtra(Intent.EXTRA_TEXT)
                        ?.takeIf(String::isNotBlank)
                        ?.let { pendingEntry.value = ExternalEntry.Trains(TrainsEntryRequest.Text(it)) }
                } else {
                    IntentCompat
                        .getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                        ?.let { pendingSharedFile.value = it }
                }
                return
            }
            Intent.ACTION_VIEW -> {
                TicketShareLinks.parsePnr(intent.dataString)?.let { pnr ->
                    pendingEntry.value = ExternalEntry.Trains(TrainsEntryRequest.Pnr(pnr))
                    return
                }
            }
        }
        JourneysDeepLink.fromIntent(intent)?.let { pendingDeepLink.value = it }
    }

    private companion object {
        const val MIME_TEXT_PLAIN = "text/plain"
    }
}

/**
 * Which feature form an external launch (share sheet / link) is hosting. [nonce]
 * makes two arrivals of the SAME request distinct so the hosted form restarts
 * (e.g. the same PNR link tapped twice while the form is still open).
 */
private sealed interface ExternalEntry {
    val nonce: Long

    data class Trains(
        val request: TrainsEntryRequest,
        override val nonce: Long = System.nanoTime(),
    ) : ExternalEntry

    data class Flights(
        val request: FlightsEntryRequest,
        override val nonce: Long = System.nanoTime(),
    ) : ExternalEntry
}

private fun IntakeRoute.toExternalEntry(): ExternalEntry =
    when (this) {
        is IntakeRoute.TrainTicket -> ExternalEntry.Trains(TrainsEntryRequest.File(Uri.parse(uri)))
        is IntakeRoute.FlightBoardingPass -> ExternalEntry.Flights(FlightsEntryRequest.BoardingPass(uri))
        is IntakeRoute.FlightBookingConfirmation -> ExternalEntry.Flights(FlightsEntryRequest.BookingConfirmation(uri))
    }

/**
 * Drives the "What's this file?" intake: starts detection when a shared file
 * arrives, shows the dialog while active, and hands the confirmed route back.
 */
@Composable
private fun SharedFileIntakeHost(
    viewModel: SharedFileIntakeViewModel,
    sharedFile: Uri?,
    onRouted: (IntakeRoute) -> Unit,
    onCancelled: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(sharedFile) {
        if (sharedFile != null) viewModel.start(sharedFile.toString()) else viewModel.reset()
    }
    LaunchedEffect(state.route) {
        state.route?.let { route ->
            onRouted(route)
            viewModel.reset()
        }
    }
    if (state.active && state.route == null) {
        SharedFileIntakeDialog(
            state = state,
            onSelect = viewModel::select,
            onConfirm = viewModel::confirm,
            onCancel = {
                viewModel.reset()
                onCancelled()
            },
        )
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
