package com.itsluminous.cleartravel.feature.itinerary.form

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.hasMapsApiKey
import com.itsluminous.cleartravel.feature.itinerary.hasPlayServices
import com.itsluminous.cleartravel.feature.itinerary.logic.MapPoint
import com.itsluminous.cleartravel.feature.itinerary.logic.formatLatLng
import com.itsluminous.cleartravel.feature.itinerary.logic.mapUnavailableReason

/** Default picker start (central India) when the item has no coordinates yet. */
private val DEFAULT_PICKER_POINT = MapPoint(21.0, 78.0)
private const val PICKER_ZOOM_WITH_INITIAL = 14f
private const val PICKER_ZOOM_DEFAULT = 4f

/**
 * Center-pin location picker: the map pans under a fixed center pin; confirming
 * reads the camera target. Degrades to an inline notice (manual lat,lng entry stays
 * available on the form) when the map cannot render.
 */
@Composable
internal fun LocationPickerView(
    initial: MapPoint?,
    onCancel: () -> Unit,
    onConfirm: (MapPoint) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val unavailableReason =
        remember { mapUnavailableReason(hasPlayServices(context), hasMapsApiKey(context)) }
    if (unavailableReason != null) {
        Column(modifier = modifier.fillMaxSize()) {
            EmptyState(
                icon = Icons.Filled.CloudOff,
                title = stringResource(R.string.itinerary_location_picker_title),
                message = stringResource(R.string.itinerary_location_picker_unavailable),
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth().padding(16.dp),
            ) { Text(stringResource(R.string.itinerary_back)) }
        }
        return
    }

    val start = initial ?: DEFAULT_PICKER_POINT
    val cameraPositionState =
        rememberCameraPositionState {
            position =
                CameraPosition.fromLatLngZoom(
                    LatLng(start.latitude, start.longitude),
                    if (initial != null) PICKER_ZOOM_WITH_INITIAL else PICKER_ZOOM_DEFAULT,
                )
        }

    Column(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f)) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
            )
            Icon(
                imageVector = Icons.Filled.Place,
                contentDescription = stringResource(R.string.itinerary_center_pin),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp).align(Alignment.Center),
            )
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                shape = MaterialTheme.shapes.medium,
                tonalElevation = 4.dp,
            ) {
                Text(
                    text = stringResource(R.string.itinerary_location_picker_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        val target = cameraPositionState.position.target
        Text(
            text = formatLatLng(target.latitude, target.longitude),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.itinerary_cancel))
            }
            Button(
                onClick = { onConfirm(MapPoint(target.latitude, target.longitude)) },
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) { Text(stringResource(R.string.itinerary_confirm_location)) }
        }
    }
}
