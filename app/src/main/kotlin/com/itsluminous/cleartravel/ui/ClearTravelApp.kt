package com.itsluminous.cleartravel.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.itsluminous.cleartravel.R
import com.itsluminous.cleartravel.core.data.crosstab.JourneyAddResult
import com.itsluminous.cleartravel.core.designsystem.component.LocalShellChrome
import com.itsluminous.cleartravel.core.designsystem.component.ShellChromeController
import com.itsluminous.cleartravel.core.model.JourneyType
import com.itsluminous.cleartravel.feature.checklist.CHECKLIST_ROUTE
import com.itsluminous.cleartravel.feature.checklist.checklistGraph
import com.itsluminous.cleartravel.feature.documents.DOCUMENTS_ROUTE
import com.itsluminous.cleartravel.feature.documents.documentsGraph
import com.itsluminous.cleartravel.feature.itinerary.TRIPS_ROUTE
import com.itsluminous.cleartravel.feature.itinerary.TripsLanding
import com.itsluminous.cleartravel.feature.itinerary.tripsGraph
import com.itsluminous.cleartravel.feature.menu.MENU_ROUTE
import com.itsluminous.cleartravel.feature.menu.menuGraph

/** The five bottom tabs. Labels are string resources; icons are decorative duplicates of the label. */
private data class TopLevelDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
)

private val topLevelDestinations =
    listOf(
        TopLevelDestination(TRIPS_ROUTE, R.string.nav_trips, Icons.Filled.Map),
        TopLevelDestination(JOURNEYS_ROUTE, R.string.nav_journeys, Icons.Filled.FlightTakeoff),
        TopLevelDestination(CHECKLIST_ROUTE, R.string.nav_checklist, Icons.Filled.Checklist),
        TopLevelDestination(DOCUMENTS_ROUTE, R.string.nav_documents, Icons.Filled.Folder),
        TopLevelDestination(MENU_ROUTE, R.string.nav_menu, Icons.Filled.Menu),
    )

/**
 * App shell: the five-tab bottom-navigation scaffold hosting each feature's nav graph.
 * Tab switches follow the Material guidance — state is saved/restored per tab and
 * re-selecting pops to the tab root. A pending notification [journeysDeepLink]
 * navigates to the Journeys tab and is forwarded into the tab's graph; a pending
 * [tripsLanding] does the same for the Trips tab (ADR-028). Cross-tab intents raised
 * inside the tabs — a linked journey tapped in an itinerary sheet
 * ([onOpenJourney]), a "Part of" row tapped in a journey sheet ([onOpenTrip]), a
 * finished journey-add pick ([onJourneyAddDone]) — are reported up to the activity,
 * which turns them into the next pending landing.
 */
@Composable
fun ClearTravelApp(
    modifier: Modifier = Modifier,
    journeysDeepLink: JourneysDeepLink? = null,
    onJourneysDeepLinkConsumed: () -> Unit = {},
    tripsLanding: TripsLanding? = null,
    onTripsLandingConsumed: () -> Unit = {},
    onOpenJourney: (JourneyType, String) -> Unit = { _, _ -> },
    onOpenTrip: (tripId: String) -> Unit = {},
    onJourneyAddDone: (JourneyAddResult) -> Unit = {},
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    /** The bottom-bar tab switch: save the leaving tab, restore the arriving one. */
    fun switchTab(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(journeysDeepLink) {
        if (journeysDeepLink != null) switchTab(JOURNEYS_ROUTE)
    }
    LaunchedEffect(tripsLanding) {
        if (tripsLanding != null) switchTab(TRIPS_ROUTE)
    }

    // ADR-034: the fullscreen document viewer asks for a chrome-free shell.
    val shellChrome = remember { ShellChromeController() }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            if (!shellChrome.hidden) {
                NavigationBar {
                    topLevelDestinations.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        val label = stringResource(destination.labelRes)
                        NavigationBarItem(
                            selected = selected,
                            onClick = { switchTab(destination.route) },
                            icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        CompositionLocalProvider(LocalShellChrome provides shellChrome) {
            NavHost(
                navController = navController,
                startDestination = TRIPS_ROUTE,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
            ) {
                tripsGraph(
                    landing = tripsLanding,
                    onLandingConsumed = onTripsLandingConsumed,
                    onOpenJourney = onOpenJourney,
                )
                journeysGraph(
                    deepLink = journeysDeepLink,
                    onDeepLinkConsumed = onJourneysDeepLinkConsumed,
                    onJourneyAddDone = onJourneyAddDone,
                    onOpenTrip = onOpenTrip,
                )
                checklistGraph()
                documentsGraph()
                menuGraph()
            }
        }
    }
}
