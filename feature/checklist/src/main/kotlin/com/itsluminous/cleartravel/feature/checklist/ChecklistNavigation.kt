package com.itsluminous.cleartravel.feature.checklist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

/** Route of the Checklist tab root. */
const val CHECKLIST_ROUTE = "checklist"

private const val LIST_ROUTE = "checklist_list"
private const val DETAIL_ROUTE = "checklist_detail/{$CHECKLIST_ID_ARG}"

private fun detailRoute(checklistId: String) = "checklist_detail/$checklistId"

/**
 * Integration hook for the app shell (ADR-039, mirrors `TripsLanding`): land the
 * Checklist tab on [checklistId]'s detail screen — how an imported shared checklist is
 * shown. [nonce] makes repeat landings on the same checklist distinct.
 */
data class ChecklistLanding(
    val checklistId: String,
    val nonce: Long = System.nanoTime(),
)

/**
 * Checklist tab graph. The tab hosts its own nested NavHost (list → full-screen
 * detail) so subscreens keep the bottom bar highlighted on the Checklist tab and the
 * app module never needs per-feature navigation wiring. [landing] is consumed via
 * [onLandingConsumed]; both defaulted so the existing call site is untouched.
 */
fun NavGraphBuilder.checklistGraph(
    landing: ChecklistLanding? = null,
    onLandingConsumed: () -> Unit = {},
) {
    composable(CHECKLIST_ROUTE) {
        ChecklistTabHost(landing = landing, onLandingConsumed = onLandingConsumed)
    }
}

@Composable
internal fun ChecklistTabHost(
    modifier: Modifier = Modifier,
    landing: ChecklistLanding? = null,
    onLandingConsumed: () -> Unit = {},
) {
    val navController = rememberNavController()
    NavHost(
        navController = navController,
        startDestination = LIST_ROUTE,
        modifier = modifier,
    ) {
        composable(LIST_ROUTE) {
            ChecklistListScreen(
                onOpenChecklist = { checklistId -> navController.navigate(detailRoute(checklistId)) },
            )
        }
        composable(
            route = DETAIL_ROUTE,
            arguments = listOf(navArgument(CHECKLIST_ID_ARG) { type = NavType.StringType }),
        ) {
            ChecklistDetailScreen(onBack = { navController.popBackStack() })
        }
    }

    // Declared after the NavHost so the graph is set before the effect navigates.
    LaunchedEffect(landing) {
        if (landing != null) {
            navController.navigate(detailRoute(landing.checklistId)) {
                popUpTo(LIST_ROUTE)
                launchSingleTop = true
            }
            onLandingConsumed()
        }
    }
}
