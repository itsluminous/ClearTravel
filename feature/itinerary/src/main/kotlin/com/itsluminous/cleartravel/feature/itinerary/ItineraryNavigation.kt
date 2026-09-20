package com.itsluminous.cleartravel.feature.itinerary

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.itsluminous.cleartravel.feature.itinerary.detail.TripDetailScreen
import com.itsluminous.cleartravel.feature.itinerary.form.ItineraryItemFormScreen
import com.itsluminous.cleartravel.feature.itinerary.trips.TripsScreen

/** Route of the Trips tab root (the trip list / itinerary feature). */
const val TRIPS_ROUTE = "trips"

/** Nav argument keys shared with the ViewModels' `SavedStateHandle` lookups. */
internal const val TRIP_ID_ARG = "tripId"
internal const val ITEM_ID_ARG = "itemId"
internal const val DAY_INDEX_ARG = "dayIndex"

private const val TRIP_LIST_ROUTE = "trip_list"
private const val TRIP_DETAIL_ROUTE = "trip_detail/{$TRIP_ID_ARG}"
private const val ITEM_FORM_ROUTE = "item_form/{$TRIP_ID_ARG}?$ITEM_ID_ARG={$ITEM_ID_ARG}&$DAY_INDEX_ARG={$DAY_INDEX_ARG}"

private fun tripDetailRoute(tripId: String) = "trip_detail/$tripId"

private fun itemFormRoute(
    tripId: String,
    itemId: String? = null,
    dayIndex: Int = 0,
) = "item_form/$tripId?$ITEM_ID_ARG=${itemId.orEmpty()}&$DAY_INDEX_ARG=$dayIndex"

/**
 * Trips tab graph. The tab holds a NESTED NavHost so trip detail and item form
 * destinations stay inside the feature module — the app shell's `tripsGraph()` call
 * site and [TRIPS_ROUTE] are unchanged from the skeleton.
 */
fun NavGraphBuilder.tripsGraph() {
    composable(TRIPS_ROUTE) {
        ItineraryNavHost()
    }
}

@Composable
private fun ItineraryNavHost() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = TRIP_LIST_ROUTE) {
        composable(TRIP_LIST_ROUTE) {
            TripsScreen(
                onOpenTrip = { tripId -> navController.navigate(tripDetailRoute(tripId)) },
            )
        }
        composable(
            route = TRIP_DETAIL_ROUTE,
            arguments = listOf(navArgument(TRIP_ID_ARG) { type = NavType.StringType }),
        ) {
            TripDetailScreen(
                onBack = { navController.popBackStack() },
                onAddItem = { tripId, dayIndex ->
                    navController.navigate(itemFormRoute(tripId, dayIndex = dayIndex))
                },
                onEditItem = { tripId, itemId ->
                    navController.navigate(itemFormRoute(tripId, itemId = itemId))
                },
            )
        }
        composable(
            route = ITEM_FORM_ROUTE,
            arguments =
                listOf(
                    navArgument(TRIP_ID_ARG) { type = NavType.StringType },
                    navArgument(ITEM_ID_ARG) {
                        type = NavType.StringType
                        defaultValue = ""
                    },
                    navArgument(DAY_INDEX_ARG) {
                        type = NavType.IntType
                        defaultValue = 0
                    },
                ),
        ) {
            ItineraryItemFormScreen(
                onDone = { navController.popBackStack() },
            )
        }
    }
}
