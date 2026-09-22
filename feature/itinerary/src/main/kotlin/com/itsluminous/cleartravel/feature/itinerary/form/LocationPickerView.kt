package com.itsluminous.cleartravel.feature.itinerary.form

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.rememberCameraPositionState
import com.itsluminous.cleartravel.core.designsystem.component.EmptyState
import com.itsluminous.cleartravel.core.designsystem.component.ExplainableIcon
import com.itsluminous.cleartravel.feature.itinerary.R
import com.itsluminous.cleartravel.feature.itinerary.hasMapsApiKey
import com.itsluminous.cleartravel.feature.itinerary.hasPlayServices
import com.itsluminous.cleartravel.feature.itinerary.logic.GeocodedPlace
import com.itsluminous.cleartravel.feature.itinerary.logic.MapPoint
import com.itsluminous.cleartravel.feature.itinerary.logic.formatLatLng
import com.itsluminous.cleartravel.feature.itinerary.logic.mapUnavailableReason
import com.itsluminous.cleartravel.feature.itinerary.logic.platformPlaceGeocoder
import kotlinx.coroutines.launch

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
    val geocoder = remember { platformPlaceGeocoder(context) }
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodedPlace>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun runSearch() {
        if (query.isBlank() || searching) return
        focusManager.clearFocus()
        searching = true
        scope.launch {
            results = geocoder.search(query)
            searched = true
            searching = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                searched = false
            },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.itinerary_place_search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon =
                if (query.isNotEmpty()) {
                    {
                        ExplainableIcon(
                            icon = Icons.Filled.Close,
                            explanationRes = R.string.itinerary_place_search_clear,
                            onClick = {
                                query = ""
                                results = emptyList()
                                searched = false
                            },
                        )
                    }
                } else {
                    null
                },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (searched && results.isEmpty()) {
            Text(
                text = stringResource(R.string.itinerary_place_search_no_results),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        results.forEach { place ->
            TextButton(
                onClick = {
                    cameraPositionState.position =
                        CameraPosition.fromLatLngZoom(
                            LatLng(place.point.latitude, place.point.longitude),
                            PICKER_ZOOM_WITH_INITIAL,
                        )
                    results = emptyList()
                    searched = false
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = place.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
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
