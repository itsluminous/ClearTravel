package com.itsluminous.cleartravel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.IntentCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.itsluminous.cleartravel.core.designsystem.component.LocalDocumentFileReader
import com.itsluminous.cleartravel.core.designsystem.theme.ClearTravelTheme
import com.itsluminous.cleartravel.core.model.ThemeMode
import com.itsluminous.cleartravel.core.notifications.AppLockNotifier
import com.itsluminous.cleartravel.core.notifications.NotificationChannelRegistrar
import com.itsluminous.cleartravel.core.notifications.NotificationPermissions
import com.itsluminous.cleartravel.feature.applock.AppLockGate
import com.itsluminous.cleartravel.feature.applock.AppLockLifecycleObserver
import com.itsluminous.cleartravel.feature.flights.FlightsEntryRequest
import com.itsluminous.cleartravel.feature.flights.FlightsExternalEntry
import com.itsluminous.cleartravel.feature.itinerary.TripsLanding
import com.itsluminous.cleartravel.feature.itinerary.intake.MapsLinkIntakeHost
import com.itsluminous.cleartravel.feature.trains.TrainsEntryRequest
import com.itsluminous.cleartravel.feature.trains.TrainsExternalEntry
import com.itsluminous.cleartravel.feature.trains.share.TicketShareLinks
import com.itsluminous.cleartravel.startup.AppStartupTasks
import com.itsluminous.cleartravel.ui.ClearTravelApp
import com.itsluminous.cleartravel.ui.JourneyPickCoordinator
import com.itsluminous.cleartravel.ui.JourneysDeepLink
import com.itsluminous.cleartravel.ui.ThemeViewModel
import com.itsluminous.cleartravel.ui.intake.IntakeRoute
import com.itsluminous.cleartravel.ui.intake.SharedFileIntakeDialog
import com.itsluminous.cleartravel.ui.intake.SharedFileIntakeViewModel
import com.itsluminous.cleartravel.ui.intake.SharedTextRoute
import com.itsluminous.cleartravel.ui.intake.routeSharedText
import com.itsluminous.cleartravel.ui.security.EncryptedDocumentFileReader
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Single-activity Compose shell. `singleTask` in the manifest, so notification deep
 * links, PNR share links and share-sheet sends arrive here — cold via [onCreate]'s
 * intent, warm via [onNewIntent] — instead of stacking duplicate instances.
 *
 * ADR-031: the whole tree sits behind [AppLockGate] (first-run password setup, unlock
 * by password or biometrics, storage preparation); nothing touches the database
 * before the gate opens — startup housekeeping runs from its `onUnlocked`. A
 * [FragmentActivity] because `BiometricPrompt` requires one.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    @Inject
    lateinit var channelRegistrar: NotificationChannelRegistrar

    @Inject
    lateinit var startupTasks: AppStartupTasks

    /** ADR-031: every document viewer in the app decrypts through this reader. */
    @Inject
    lateinit var documentFileReader: EncryptedDocumentFileReader

    /** ADR-031: re-locks the UI after the configured background time. */
    @Inject
    lateinit var lockLifecycleObserver: AppLockLifecycleObserver

    /** ADR-031: the "unlock to sync" nudge background jobs post before the first unlock. */
    @Inject
    lateinit var appLockNotifier: AppLockNotifier

    /** Pending notification deep link (ADR-013 contract); cleared once consumed. */
    private val pendingDeepLink = mutableStateOf<JourneysDeepLink?>(null)

    /** Pending Trips-tab landing (ADR-028: "Part of" row, or the journey-add return); cleared once consumed. */
    private val pendingTripsLanding = mutableStateOf<TripsLanding?>(null)

    /**
     * Pending external entry into a feature form — shared IRCTC text or a confirmed
     * shared file for trains, a PNR share link (ADR-020), or a confirmed shared file
     * for flights. Cleared when the hosted form closes.
     */
    private val pendingEntry = mutableStateOf<ExternalEntry?>(null)

    /** A shared image/PDF awaiting the "What's this file?" intake dialog. */
    private val pendingSharedFile = mutableStateOf<Uri?>(null)

    /** Shared text carrying a Google Maps link, awaiting the "Add place" intake (ADR-029 part D). */
    private val pendingMapsLink = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // AndroidX splash (Theme.ClearTravel.Splash): must be installed before
        // super.onCreate() so the handoff to postSplashScreenTheme is seamless.
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Idempotent channel creation — every module can post immediately after.
        channelRegistrar.registerAll()
        lockLifecycleObserver.install()

        consumeIntent(intent)

        val themeViewModel: ThemeViewModel by viewModels()
        val intakeViewModel: SharedFileIntakeViewModel by viewModels()
        val pickCoordinator: JourneyPickCoordinator by viewModels()
        setContent {
            val themeMode by themeViewModel.themeMode.collectAsStateWithLifecycle()
            NotificationPermissionEffect()
            // ADR-028: an itinerary leg asked for a new journey → land on Journeys in
            // pick mode. Once per request (ADR-029): the coordinator latches the nonce
            // so an activity re-creation with the request still pending does not re-land.
            val pickRequest by pickCoordinator.pendingRequest.collectAsStateWithLifecycle()
            LaunchedEffect(pickRequest) {
                pickCoordinator.takeLanding(pickRequest)?.let { pendingDeepLink.value = JourneysDeepLink.forJourneyAdd(it) }
            }
            ClearTravelTheme(darkTheme = themeMode.resolveDarkTheme()) {
                CompositionLocalProvider(LocalDocumentFileReader provides documentFileReader) {
                    AppLockGate(
                        // Housekeeping (auto-archive past journeys, restart the flight
                        // poll chain) runs on IO once the encrypted store is open.
                        onUnlocked = {
                            appLockNotifier.clear()
                            startupTasks.runOnAppOpen()
                        },
                    ) {
                        ShellContent(themeViewModel, intakeViewModel, pickCoordinator)
                    }
                }
            }
        }
    }

    /** The shell's content tree (external entry forms, the tabbed app, intake hosts). */
    @Composable
    private fun ShellContent(
        themeViewModel: ThemeViewModel,
        intakeViewModel: SharedFileIntakeViewModel,
        pickCoordinator: JourneyPickCoordinator,
    ) {
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
                            onDone = { result ->
                                pendingDeepLink.value = JourneysDeepLink.forFlightsEntry(result)
                                pendingEntry.value = null
                            },
                        )
                    }
                }
            null ->
                ClearTravelApp(
                    journeysDeepLink = pendingDeepLink.value,
                    onJourneysDeepLinkConsumed = { pendingDeepLink.value = null },
                    tripsLanding = pendingTripsLanding.value,
                    onTripsLandingConsumed = { pendingTripsLanding.value = null },
                    onOpenJourney = { type, id -> pendingDeepLink.value = JourneysDeepLink.forJourney(type, id) },
                    onOpenTrip = { tripId -> pendingTripsLanding.value = TripsLanding(tripId = tripId) },
                    onJourneyAddDone = { result ->
                        // Answer the bus FIRST so the restored itinerary form
                        // already holds the linked journey, then go back.
                        pickCoordinator.complete(result)
                        pendingTripsLanding.value = TripsLanding(tripId = null)
                    },
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
        // ADR-029 part D: the added place's trip is shown in the Trips tab.
        MapsLinkIntakeHost(
            sharedText = pendingMapsLink.value,
            onDone = { tripId ->
                pendingMapsLink.value = null
                pendingTripsLanding.value = TripsLanding(tripId = tripId)
            },
            onCancelled = { pendingMapsLink.value = null },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    /**
     * Routes an arriving intent: ACTION_SEND text (a Google Maps link → the "Add
     * place" intake, ADR-029; anything else → train SMS form directly), ACTION_SEND
     * image/PDF (→ intake dialog), ACTION_VIEW PNR link (→ train form carrying the
     * PNR), else a notification deep link.
     */
    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                if (intent.type == MIME_TEXT_PLAIN) {
                    when (val route = routeSharedText(intent.getStringExtra(Intent.EXTRA_TEXT))) {
                        is SharedTextRoute.MapsLink -> pendingMapsLink.value = route.text
                        is SharedTextRoute.TrainText -> pendingEntry.value = ExternalEntry.Trains(TrainsEntryRequest.Text(route.text))
                        null -> Unit
                    }
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
