package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.database.SoundDropEntity
import com.example.data.database.VirtualGraffitiEntity
import com.example.ui.theme.AcidGreen
import com.example.ui.theme.DarkGreyCarbon
import com.example.ui.theme.GuerrillaPink
import com.example.ui.theme.LaserCyan
import com.example.ui.theme.MutedText
import com.example.ui.theme.WarningYellow
import com.example.ui.theme.WhiteText
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapType
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.rememberCameraPositionState
import kotlinx.coroutines.launch

private const val DARK_CYBER_MAP_STYLE_JSON = """
[
  { "elementType": "geometry", "stylers": [{ "color": "#0d0d12" }] },
  { "elementType": "labels.icon", "stylers": [{ "visibility": "off" }] },
  { "elementType": "labels.text.fill", "stylers": [{ "color": "#758599" }] },
  { "elementType": "labels.text.stroke", "stylers": [{ "color": "#0d0d12" }] },
  {
    "featureType": "administrative",
    "elementType": "geometry",
    "stylers": [{ "color": "#262633" }]
  },
  {
    "featureType": "poi",
    "elementType": "geometry",
    "stylers": [{ "color": "#14141b" }]
  },
  {
    "featureType": "poi.park",
    "elementType": "geometry",
    "stylers": [{ "color": "#101a16" }]
  },
  {
    "featureType": "road",
    "elementType": "geometry.fill",
    "stylers": [{ "color": "#1c1c26" }]
  },
  {
    "featureType": "road",
    "elementType": "geometry.stroke",
    "stylers": [{ "color": "#2a2a38" }]
  },
  {
    "featureType": "road.arterial",
    "elementType": "geometry.fill",
    "stylers": [{ "color": "#22222e" }]
  },
  {
    "featureType": "road.highway",
    "elementType": "geometry.fill",
    "stylers": [{ "color": "#2b2b3a" }]
  },
  {
    "featureType": "transit",
    "elementType": "geometry",
    "stylers": [{ "color": "#171720" }]
  },
  {
    "featureType": "water",
    "elementType": "geometry",
    "stylers": [{ "color": "#060b14" }]
  }
]
"""

fun isLikelyValidMapsApiKey(apiKey: String = BuildConfig.MAPS_API_KEY): Boolean {
    val trimmed = apiKey.trim()
    return trimmed.isNotEmpty() &&
        trimmed != "MY_MAPS_API_KEY" &&
        trimmed.startsWith("AIza") &&
        trimmed.length >= 30
}

/**
 * Unified Google Map view displaying:
 * - Concentric radar range rings around the user's position
 * - Shaded circular geofences & markers for Sound-Drops
 * - Virtual AR Graffiti anchor pins
 * - Tap-to-pin drop coordinate targeting and floating Sound-Drop playback inspector
 */
@Composable
fun SoundDropGoogleMap(
    userLat: Double,
    userLng: Double,
    soundDrops: List<SoundDropEntity>,
    graffitiList: List<VirtualGraffitiEntity>,
    playingDropId: Int?,
    selectedDrop: SoundDropEntity?,
    pinnedDropTarget: LatLng?,
    hasLocationPermission: Boolean,
    distanceCalculator: (Double, Double, Double, Double) -> Double,
    onSoundDropSelected: (SoundDropEntity) -> Unit,
    onMapCoordinateTapped: (LatLng) -> Unit,
    onClearPinnedTarget: () -> Unit,
    onDropAtPinnedTarget: () -> Unit,
    onRecenterToUser: () -> Unit,
    onToggleSoundDropPlayback: (SoundDropEntity) -> Unit,
    onSimulateWalkToDrop: (SoundDropEntity) -> Unit,
    onDeleteSoundDrop: (SoundDropEntity) -> Unit,
    onClearSelectedDrop: () -> Unit,
    modifier: Modifier = Modifier,
    activeGeohashTile: String = "",
    communityCount: Int = 0
) {
    val hasValidKeyFormat = remember { isLikelyValidMapsApiKey() }
    val userLatLng = remember(userLat, userLng) { LatLng(userLat, userLng) }
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(userLatLng, 16.5f)
    }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(userLat, userLng) {
        cameraPositionState.animate(
            update = CameraUpdateFactory.newLatLng(LatLng(userLat, userLng)),
            durationMs = 450
        )
    }

    val mapStyleOptions = remember {
        MapStyleOptions(DARK_CYBER_MAP_STYLE_JSON)
    }

    val mapProperties = remember(hasLocationPermission, mapStyleOptions) {
        MapProperties(
            isMyLocationEnabled = false,
            mapStyleOptions = mapStyleOptions,
            mapType = MapType.NORMAL
        )
    }

    val mapUiSettings = remember {
        MapUiSettings(
            zoomControlsEnabled = false,
            compassEnabled = true,
            myLocationButtonEnabled = false,
            mapToolbarEnabled = false,
            tiltGesturesEnabled = true,
            rotationGesturesEnabled = true
        )
    }

    var dismissKeyNotice by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier
                .fillMaxSize()
                .testTag("sound_drop_google_map"),
            cameraPositionState = cameraPositionState,
            properties = mapProperties,
            uiSettings = mapUiSettings,
            onMapClick = { latLng ->
                onClearSelectedDrop()
                onMapCoordinateTapped(latLng)
            }
        ) {
            // 1. Unified User Position + Radar Scanner Rings directly on Google Map
            val userMarkerState = remember { MarkerState(position = userLatLng) }
            LaunchedEffect(userLatLng) {
                userMarkerState.position = userLatLng
            }
            Circle(
                center = userLatLng,
                radius = 150.0,
                fillColor = Color.Transparent,
                strokeColor = LaserCyan.copy(alpha = 0.22f),
                strokeWidth = 2f
            )
            Circle(
                center = userLatLng,
                radius = 60.0,
                fillColor = LaserCyan.copy(alpha = 0.06f),
                strokeColor = LaserCyan.copy(alpha = 0.45f),
                strokeWidth = 2f
            )
            Circle(
                center = userLatLng,
                radius = 18.0,
                fillColor = LaserCyan.copy(alpha = 0.18f),
                strokeColor = LaserCyan.copy(alpha = 0.85f),
                strokeWidth = 2.5f
            )
            Marker(
                state = userMarkerState,
                title = "2ND UNIT POSITION",
                snippet = String.format("%.5f, %.5f", userLat, userLng),
                icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE)
            )

            // 2. Sound Drop Circular Geofence Spheres + Interactive Markers
            soundDrops.forEach { drop ->
                key(drop.id, drop.cloudId) {
                    val dropPos = LatLng(drop.latitude, drop.longitude)
                    val dist = distanceCalculator(userLat, userLng, drop.latitude, drop.longitude)
                    val inRange = dist <= drop.radiusInMeters
                    val isPlaying = playingDropId == drop.id
                    val isSelected = selectedDrop?.id == drop.id

                    val zoneColor = when {
                        isPlaying || inRange -> AcidGreen
                        drop.isFromCommunity -> WarningYellow
                        else -> GuerrillaPink
                    }

                    Circle(
                        center = dropPos,
                        radius = drop.radiusInMeters,
                        fillColor = zoneColor.copy(alpha = if (isSelected) 0.24f else 0.12f),
                        strokeColor = zoneColor.copy(alpha = if (isSelected) 0.95f else 0.6f),
                        strokeWidth = if (isSelected) 5f else 3f,
                        clickable = true,
                        onClick = { onSoundDropSelected(drop) }
                    )

                    val markerState = remember(drop.id, drop.cloudId, drop.publicId) { MarkerState(position = dropPos) }
                    LaunchedEffect(dropPos) {
                        markerState.position = dropPos
                    }
                    val markerHue = when {
                        isPlaying || inRange -> BitmapDescriptorFactory.HUE_GREEN
                        drop.isFromCommunity -> BitmapDescriptorFactory.HUE_ORANGE
                        else -> BitmapDescriptorFactory.HUE_ROSE
                    }
                    val originTag = if (drop.isFromCommunity) "PUBLIC" else "OWN"
                    Marker(
                        state = markerState,
                        title = drop.title,
                        snippet = "$originTag • ${dist.toInt()}m • R:${drop.radiusInMeters.toInt()}m • ${drop.dspPreset} • ${drop.creatorName}",
                        icon = BitmapDescriptorFactory.defaultMarker(markerHue),
                        onClick = {
                            onSoundDropSelected(drop)
                            false
                        }
                    )
                }
            }

            // 3. Virtual Graffiti Spatial Anchor Markers on Map
            graffitiList.forEach { graffiti ->
                key("g_${graffiti.id}_${graffiti.cloudId}_${graffiti.publicId}") {
                    val gPos = LatLng(graffiti.latitude, graffiti.longitude)
                    val markerState = remember(graffiti.id, graffiti.cloudId, graffiti.publicId) { MarkerState(position = gPos) }
                    LaunchedEffect(gPos) {
                        markerState.position = gPos
                    }
                    val graffitiHue = if (graffiti.isFromCommunity) {
                        BitmapDescriptorFactory.HUE_VIOLET
                    } else {
                        BitmapDescriptorFactory.HUE_CYAN
                    }
                    Marker(
                        state = markerState,
                        title = "AR[${graffiti.mediaType}]: ${graffiti.contentText}",
                        snippet = "${graffiti.anchorType} • ${graffiti.anchorAzimuth.toInt()}° • By ${graffiti.author}",
                        icon = BitmapDescriptorFactory.defaultMarker(graffitiHue),
                        alpha = 0.88f
                    )
                }
            }

            // 4. Tap-to-pin Target Drop Pin
            if (pinnedDropTarget != null) {
                val targetState = remember { MarkerState(position = pinnedDropTarget) }
                LaunchedEffect(pinnedDropTarget) {
                    targetState.position = pinnedDropTarget
                }
                Circle(
                    center = pinnedDropTarget,
                    radius = 150.0,
                    fillColor = WarningYellow.copy(alpha = 0.08f),
                    strokeColor = WarningYellow.copy(alpha = 0.7f),
                    strokeWidth = 3f
                )
                Marker(
                    state = targetState,
                    title = "TARGET DROP SITE",
                    snippet = String.format("%.5f, %.5f", pinnedDropTarget.latitude, pinnedDropTarget.longitude),
                    icon = BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_YELLOW)
                )
            }
        }

        // Top-right Recenter & optional API key notice
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            if (!hasValidKeyFormat && !dismissKeyNotice) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.92f)),
                    border = BorderStroke(1.dp, WarningYellow.copy(alpha = 0.7f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.widthIn(max = 220.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Key,
                            contentDescription = "Maps API key notice",
                            tint = WarningYellow,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "SET MAPS_API_KEY IN SECRETS",
                            color = WarningYellow,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss key notice",
                            tint = MutedText,
                            modifier = Modifier
                                .size(16.dp)
                                .clickable { dismissKeyNotice = true }
                        )
                    }
                }
            }

            if (activeGeohashTile.isNotBlank()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.9f)),
                    border = BorderStroke(1.dp, LaserCyan.copy(alpha = 0.55f)),
                    shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.testTag("geohash_tile_badge")
                ) {
                    Text(
                        text = "CELL $activeGeohashTile • $communityCount PUBLIC",
                        color = LaserCyan,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                    )
                }
            }

            SmallFloatingActionButton(
                onClick = {
                    onRecenterToUser()
                    coroutineScope.launch {
                        cameraPositionState.animate(
                            CameraUpdateFactory.newLatLngZoom(LatLng(userLat, userLng), 16.5f),
                            durationMs = 450
                        )
                    }
                },
                containerColor = DarkGreyCarbon.copy(alpha = 0.9f),
                contentColor = LaserCyan,
                shape = CircleShape,
                modifier = Modifier.testTag("recenter_map_button")
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = "Recenter map on unit position"
                )
            }
        }

        // Action Pill when user taps a target coordinate on Google Map
        AnimatedVisibility(
            visible = pinnedDropTarget != null && selectedDrop == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp)
        ) {
            pinnedDropTarget?.let { target ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.94f)),
                    border = BorderStroke(1.dp, WarningYellow),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Place,
                            contentDescription = null,
                            tint = WarningYellow,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = String.format("%.4f, %.4f", target.latitude, target.longitude),
                            color = WhiteText,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onDropAtPinnedTarget,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                            modifier = Modifier.testTag("drop_at_pin_button")
                        ) {
                            Text(
                                text = "DROP HERE",
                                color = WarningYellow,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        IconButton(
                            onClick = onClearPinnedTarget,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear target pin",
                                tint = MutedText,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }
        }

        // Selected Sound Drop Floating Inspector Card
        AnimatedVisibility(
            visible = selectedDrop != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 16.dp, end = 84.dp, bottom = 16.dp)
        ) {
            selectedDrop?.let { drop ->
                val dist = distanceCalculator(userLat, userLng, drop.latitude, drop.longitude)
                val inRange = dist <= drop.radiusInMeters
                val isPlaying = playingDropId == drop.id

                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.95f)),
                    border = BorderStroke(1.dp, if (inRange) AcidGreen else GuerrillaPink),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("selected_drop_map_card")
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(if (inRange) AcidGreen else GuerrillaPink)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = drop.title,
                                    color = WhiteText,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!drop.isFromCommunity && drop.cloudId != null) {
                                IconButton(
                                    onClick = { onDeleteSoundDrop(drop) },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = "Delete sound drop",
                                        tint = GuerrillaPink,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                            }
                            IconButton(
                                onClick = onClearSelectedDrop,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close sound drop card",
                                    tint = MutedText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))
                        val sourceTag = when {
                            !drop.audioUrl.isNullOrBlank() -> "CLOUD"
                            !drop.localAudioPath.isNullOrBlank() -> "MIC"
                            else -> "SYNTH"
                        }
                        val originLabel = if (drop.isFromCommunity) "PUBLIC" else "OWN"
                        Text(
                            text = "${dist.toInt()}m away • R:${drop.radiusInMeters.toInt()}m • ${drop.creatorName} • $originLabel • $sourceTag • DSP:${drop.dspPreset} • ${formatUploadTimestamp(drop.timestamp)}",
                            color = if (inRange) AcidGreen else if (drop.isFromCommunity) WarningYellow else GuerrillaPink,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace
                        )
                        if (drop.description.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = drop.description,
                                color = MutedText,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (inRange) {
                                Button(
                                    onClick = { onToggleSoundDropPlayback(drop) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPlaying) GuerrillaPink else AcidGreen
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.Black,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isPlaying) "STOP" else "LISTEN",
                                        color = Color.Black,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onSimulateWalkToDrop(drop) },
                                    border = BorderStroke(1.dp, LaserCyan),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Filled.DirectionsWalk,
                                        contentDescription = null,
                                        tint = LaserCyan,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "MOVE INTO SPHERE",
                                        color = LaserCyan,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
