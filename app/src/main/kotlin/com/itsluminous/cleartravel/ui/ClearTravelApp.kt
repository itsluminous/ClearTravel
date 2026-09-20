package com.itsluminous.cleartravel.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.itsluminous.cleartravel.R
import com.itsluminous.cleartravel.feature.checklist.CHECKLIST_ROUTE
import com.itsluminous.cleartravel.feature.checklist.checklistGraph
import com.itsluminous.cleartravel.feature.itinerary.TRIPS_ROUTE
import com.itsluminous.cleartravel.feature.itinerary.tripsGraph
import com.itsluminous.cleartravel.feature.menu.MENU_ROUTE
import com.itsluminous.cleartravel.feature.menu.menuGraph

/** The four bottom tabs. Labels are string resources; icons are decorative duplicates of the label. */
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
        TopLevelDestination(MENU_ROUTE, R.string.nav_menu, Icons.Filled.Menu),
    )

/**
 * App shell: the four-tab bottom-navigation scaffold hosting each feature's nav graph.
 * Tab switches follow the Material guidance — state is saved/restored per tab and
 * re-selecting pops to the tab root.
 */
@Composable
fun ClearTravelApp(modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { destination ->
                    val selected =
                        currentDestination?.hierarchy?.any { it.route == destination.route } == true
                    val label = stringResource(destination.labelRes)
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(imageVector = destination.icon, contentDescription = null) },
                        label = { Text(label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = TRIPS_ROUTE,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .consumeWindowInsets(padding),
        ) {
            tripsGraph()
            journeysGraph()
            checklistGraph()
            menuGraph()
        }
    }
}
