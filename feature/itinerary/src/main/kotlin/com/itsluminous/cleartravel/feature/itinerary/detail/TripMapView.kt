package com.itsluminous.cleartravel.feature.itinerary.detail

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.core.model.ItineraryItem
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.hasLocationPermission
import com.itsluminous.cleartravel.feature.itinerary.hasMapsApiKey
import com.itsluminous.cleartravel.feature.itinerary.hasPlayServices
import com.itsluminous.cleartravel.feature.itinerary.logic.MapUnavailableReason
import com.itsluminous.cleartravel.feature.itinerary.logic.TripMapContent
import com.itsluminous.cleartravel.feature.itinerary.logic.dayColorArgb
import com.itsluminous.cleartravel.feature.itinerary.logic.mapUnavailableReason

/**
 * Map toggle of the trip screen. Renders the GoogleMap ONLY when the readiness
 * guard passes ([mapUnavailableReason]); otherwise it degrades to an inline notice
 * so the offline-first timeline stays fully usable. Markers are numbered per day
 * and colored by the stable day palette; polylines connect each day's items.
 */
@Composable
internal fun TripMapView(
    content: TripMapContent,
    onMarkerClick: (ItineraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val unavailableReason =
        remember { mapUnavailableReason(hasPlayServices(context), hasMapsApiKey(context)) }
    when {
        unavailableReason != null ->
            EmptyState(
                icon = Icons.Filled.CloudOff,
                title = stringResource(R.string.itinerary_view_map),
                message =
                    stringResource(
                        when (unavailableReason) {
                            MapUnavailableReason.MISSING_PLAY_SERVICES ->
                                R.string.itinerary_map_unavailable_play
                            MapUnavailableReason.MISSING_API_KEY ->
                                R.string.itinerary_map_unavailable_key
                        },
                    ),
                modifier = modifier,
            )
        content.isEmpty ->
            EmptyState(
                icon = Icons.Filled.Map,
                title = stringResource(R.string.itinerary_map_empty_title),
                message = stringResource(R.string.itinerary_map_empty_message),
                modifier = modifier,
            )
        else -> ReadyMap(content = content, onMarkerClick = onMarkerClick, modifier = modifier)
    }
}

@Composable
private fun ReadyMap(
    content: TripMapContent,
    onMarkerClick: (ItineraryItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var locationGranted by remember { mutableStateOf(hasLocationPermission(context)) }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            locationGranted = grants.values.any { it }
        }

    val allPoints =
        content.markers.map { it.point } + content.paths.flatMap { it.points }
    val firstPoint = allPoints.firstOrNull()
    val cameraPositionState =
        rememberCameraPositionState {
            if (firstPoint != null) {
                position =
                    CameraPosition.fromLatLngZoom(
                        LatLng(firstPoint.latitude, firstPoint.longitude),
                        11f,
                    )
            }
        }
    var mapLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(mapLoaded, content) {
        if (!mapLoaded || allPoints.size < 2) return@LaunchedEffect
        val bounds =
            LatLngBounds
                .builder()
                .apply { allPoints.forEach { include(LatLng(it.latitude, it.longitude)) } }
                .build()
        // Guarded: bounds math can fail on degenerate layouts; never crash the screen.
        runCatching { cameraPositionState.move(CameraUpdateFactory.newLatLngBounds(bounds, 96)) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = cameraPositionState,
            properties = MapProperties(isMyLocationEnabled = locationGranted),
            onMapLoaded = { mapLoaded = true },
        ) {
            content.paths.forEach { path ->
                Polyline(
                    points = path.points.map { LatLng(it.latitude, it.longitude) },
                    color = Color(dayColorArgb(path.dayIndex)),
                    width = 8f,
                )
            }
            content.markers.forEach { marker ->
                val markerState =
                    remember(marker.item.id, marker.point) {
                        MarkerState(LatLng(marker.point.latitude, marker.point.longitude))
                    }
                val markerColor = Color(dayColorArgb(marker.dayIndex))
                MarkerComposable(
                    marker.item.id,
                    marker.dayIndex,
                    marker.numberInDay,
                    state = markerState,
                    title = marker.item.name,
                    onClick = {
                        onMarkerClick(marker.item)
                        true
                    },
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(32.dp)
                                .background(markerColor, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = marker.numberInDay.toString(),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        if (!locationGranted) {
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                shape = CircleShape,
                tonalElevation = 4.dp,
            ) {
                ExplainableIcon(
                    icon = Icons.Filled.LocationSearching,
                    explanationRes = R.string.itinerary_enable_my_location,
                    onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                            ),
                        )
                    },
                )
            }
        }
    }
}
