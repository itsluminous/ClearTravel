package com.itsluminous.cleartravel.feature.itinerary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Attractions
import androidx.compose.material.icons.filled.Commute
import androidx.compose.material.icons.filled.DirectionsBoat
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Hotel
import androidx.compose.material.icons.filled.LocalTaxi
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material.icons.filled.Train
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.itsluminous.cleartravel.core.model.CommuteMode
import com.itsluminous.cleartravel.core.model.PlaceCategory
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Localized snackbar text for a ViewModel message. */
@StringRes
internal fun ItineraryMessage.labelRes(): Int =
    when (this) {
        ItineraryMessage.TRIP_NAME_REQUIRED -> R.string.itinerary_msg_trip_name_required
        ItineraryMessage.TRIP_DATES_INVALID -> R.string.itinerary_msg_trip_dates_invalid
        ItineraryMessage.TRIP_SAVED -> R.string.itinerary_msg_trip_saved
        ItineraryMessage.TRIP_DELETED -> R.string.itinerary_msg_trip_deleted
        ItineraryMessage.TRIP_ARCHIVED -> R.string.itinerary_msg_trip_archived
        ItineraryMessage.TRIP_UNARCHIVED -> R.string.itinerary_msg_trip_unarchived
        ItineraryMessage.ITEM_NAME_REQUIRED -> R.string.itinerary_msg_item_name_required
        ItineraryMessage.ITEM_ROUTE_REQUIRED -> R.string.itinerary_msg_item_route_required
        ItineraryMessage.ITEM_SAVED -> R.string.itinerary_msg_item_saved
        ItineraryMessage.ITEM_DELETED -> R.string.itinerary_msg_item_deleted
    }

@StringRes
internal fun PlaceCategory.labelRes(): Int =
    when (this) {
        PlaceCategory.SIGHT -> R.string.itinerary_category_sight
        PlaceCategory.FOOD -> R.string.itinerary_category_food
        PlaceCategory.STAY -> R.string.itinerary_category_stay
        PlaceCategory.SHOPPING -> R.string.itinerary_category_shopping
        PlaceCategory.ACTIVITY -> R.string.itinerary_category_activity
        PlaceCategory.OTHER -> R.string.itinerary_category_other
    }

internal fun PlaceCategory.icon(): ImageVector =
    when (this) {
        PlaceCategory.SIGHT -> Icons.Filled.Attractions
        PlaceCategory.FOOD -> Icons.Filled.Restaurant
        PlaceCategory.STAY -> Icons.Filled.Hotel
        PlaceCategory.SHOPPING -> Icons.Filled.ShoppingBag
        PlaceCategory.ACTIVITY -> Icons.Filled.Hiking
        PlaceCategory.OTHER -> Icons.Filled.Place
    }

@StringRes
internal fun CommuteMode.labelRes(): Int =
    when (this) {
        CommuteMode.TRAIN -> R.string.itinerary_mode_train
        CommuteMode.FLIGHT -> R.string.itinerary_mode_flight
        CommuteMode.CAB -> R.string.itinerary_mode_cab
        CommuteMode.BUS -> R.string.itinerary_mode_bus
        CommuteMode.WALK -> R.string.itinerary_mode_walk
        CommuteMode.FERRY -> R.string.itinerary_mode_ferry
        CommuteMode.OTHER -> R.string.itinerary_mode_other
    }

internal fun CommuteMode.icon(): ImageVector =
    when (this) {
        CommuteMode.TRAIN -> Icons.Filled.Train
        CommuteMode.FLIGHT -> Icons.Filled.Flight
        CommuteMode.CAB -> Icons.Filled.LocalTaxi
        CommuteMode.BUS -> Icons.Filled.DirectionsBus
        CommuteMode.WALK -> Icons.Filled.DirectionsWalk
        CommuteMode.FERRY -> Icons.Filled.DirectionsBoat
        CommuteMode.OTHER -> Icons.Filled.Commute
    }

/** Parses a stored "#AARRGGBB" cover color; null for empty/garbage values. */
internal fun parseCoverColor(hex: String): Color? =
    if (hex.isBlank()) null else runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrNull()

private val tripDateFormatter: DateTimeFormatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

internal fun LocalDate.formatMedium(): String = format(tripDateFormatter)

/** Whether the manifest carries a non-empty Maps API key (empty-safe placeholder). */
internal fun hasMapsApiKey(context: Context): Boolean =
    runCatching {
        val appInfo =
            context.packageManager.getApplicationInfo(
                context.packageName,
                PackageManager.GET_META_DATA,
            )
        !appInfo.metaData?.getString("com.google.android.geo.API_KEY").isNullOrBlank()
    }.getOrDefault(false)

internal fun hasPlayServices(context: Context): Boolean =
    GoogleApiAvailability
        .getInstance()
        .isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS

internal fun hasLocationPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
