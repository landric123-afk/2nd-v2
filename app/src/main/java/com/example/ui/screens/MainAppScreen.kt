package com.example.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.example.ar.ArCoreAvailabilityStatus
import com.example.ar.ArCoreCameraSurface
import com.example.ar.ArSessionLifecycleEffect
import com.example.ar.GeospatialVpsTelemetry
import com.example.ar.VpsAnchorSurfaceMode
import com.example.audio.VoiceFilterPreset
import com.example.data.database.SoundDropEntity
import com.example.data.database.VirtualGraffitiEntity
import com.example.location.LocationPermissionCard
import com.example.location.LocationPermissionController
import com.example.location.LocationPermissionState
import com.example.location.LocationPermissionStatus
import com.example.location.rememberLocationPermissionController
import com.example.ui.components.MinimalTimelineScrollWheel
import com.example.ui.components.SoundDropGoogleMap
import com.example.ui.components.formatUploadTimestamp
import com.example.ui.theme.*
import com.example.ui.viewmodel.MainViewModel
import com.google.android.gms.maps.model.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppScreen(
    viewModel: MainViewModel,
    onSignedOut: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(0) } // 0 = Unified Google Map, 1 = AR Scanner, 2 = Profile

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasRequestedOtherPermissions by remember { mutableStateOf(false) }

    val reqLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasCameraPermission = results[Manifest.permission.CAMERA] ?: hasCameraPermission
        hasAudioPermission = results[Manifest.permission.RECORD_AUDIO] ?: hasAudioPermission
    }

    val launchOtherPermissions: () -> Unit = {
        if (!hasRequestedOtherPermissions) {
            hasRequestedOtherPermissions = true
            val otherPermissions = buildList {
                add(Manifest.permission.CAMERA)
                add(Manifest.permission.RECORD_AUDIO)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            reqLauncher.launch(otherPermissions)
        }
    }

    val locationPermissionController = rememberLocationPermissionController { updatedState ->
        if (updatedState.hasAnyLocation) {
            viewModel.startLiveLocationUpdates(updatedState.hasFineLocation)
            viewModel.syncRealGpsLocation()
        } else {
            viewModel.stopLiveLocationUpdates()
        }
        launchOtherPermissions()
    }

    LaunchedEffect(locationPermissionController.state.hasAnyLocation, locationPermissionController.state.hasFineLocation) {
        if (locationPermissionController.state.hasAnyLocation) {
            viewModel.startLiveLocationUpdates(locationPermissionController.state.hasFineLocation)
            viewModel.syncRealGpsLocation()
        }
    }

    LaunchedEffect(Unit) {
        if (!locationPermissionController.state.hasAnyLocation) {
            locationPermissionController.requestPermission()
        } else {
            launchOtherPermissions()
        }
    }

    val mediaSyncNotice by viewModel.mediaSyncNotice.collectAsState()

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = BlackCarbon,
        bottomBar = {
            NavigationBar(
                containerColor = DarkGreyCarbon,
                tonalElevation = 8.dp,
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .testTag("main_navigation_bar")
            ) {
                NavigationBarItem(
                    selected = activeTab == 0,
                    onClick = { activeTab = 0 },
                    icon = { Icon(Icons.Default.Map, contentDescription = "Unified Sound-Drop Map") },
                    label = { Text("MAP") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = AcidGreen,
                        selectedTextColor = AcidGreen,
                        indicatorColor = LightGreyCarbon,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    ),
                    modifier = Modifier.testTag("nav_item_map")
                )
                NavigationBarItem(
                    selected = activeTab == 1,
                    onClick = { activeTab = 1 },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = "AR Graffiti Scanner") },
                    label = { Text("AR") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = LaserCyan,
                        selectedTextColor = LaserCyan,
                        indicatorColor = LightGreyCarbon,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    ),
                    modifier = Modifier.testTag("nav_item_ar")
                )
                NavigationBarItem(
                    selected = activeTab == 2,
                    onClick = { activeTab = 2 },
                    icon = { Icon(Icons.Default.Person, contentDescription = "Operator Profile") },
                    label = { Text("PROFILE") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = GuerrillaPink,
                        selectedTextColor = GuerrillaPink,
                        indicatorColor = LightGreyCarbon,
                        unselectedIconColor = MutedText,
                        unselectedTextColor = MutedText
                    ),
                    modifier = Modifier.testTag("nav_item_profile")
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (activeTab) {
                0 -> MapRadarScreen(
                    viewModel = viewModel,
                    hasAudioPermission = hasAudioPermission,
                    onRequestAudioPermission = {
                        reqLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                    },
                    locationPermissionController = locationPermissionController
                )
                1 -> ArScannerScreen(viewModel, hasCameraPermission)
                2 -> ProfileScreen(viewModel = viewModel, onSignedOut = onSignedOut)
            }

            AnimatedVisibility(
                visible = mediaSyncNotice != null,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                mediaSyncNotice?.let { notice ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.96f)),
                        border = BorderStroke(1.dp, WarningYellow),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                tint = WarningYellow,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = notice,
                                color = WarningYellow,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.clearMediaSyncNotice() },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss cloud storage notice",
                                    tint = MutedText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// UNIFIED GOOGLE MAP + GEOFENCE RADAR COMPOSABLE
// ----------------------------------------------------
@Composable
fun MapRadarScreen(
    viewModel: MainViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    locationPermissionController: LocationPermissionController
) {
    val userLat by viewModel.userLatitude.collectAsState()
    val userLng by viewModel.userLongitude.collectAsState()
    val isLiveGpsActive by viewModel.isLiveGpsActive.collectAsState()
    val soundDrops by viewModel.soundDropsList.collectAsState()
    val graffitiList by viewModel.virtualGraffitiList.collectAsState()
    val activeFilterHours by viewModel.timelineFilterHours.collectAsState()
    val uploadMarkers by viewModel.timelineUploadMarkers.collectAsState()
    val playingDropId by viewModel.playingDropId.collectAsState()
    val activeGeohashTile by viewModel.activeGeohashTile.collectAsState()
    val communityCount by viewModel.communityDiscoveryCount.collectAsState()
    val locationState = locationPermissionController.state
    val hasLocationPermission = locationState.hasAnyLocation

    val latestUploadTimestamp = remember(soundDrops, graffitiList) {
        listOfNotNull(
            soundDrops.maxOfOrNull { it.timestamp },
            graffitiList.maxOfOrNull { it.timestamp }
        ).maxOrNull()
    }

    var selectedDrop by remember { mutableStateOf<SoundDropEntity?>(null) }
    var pinnedDropTarget by remember { mutableStateOf<LatLng?>(null) }
    var dismissLocationBanner by remember { mutableStateOf(false) }
    var showDropSheet by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // 1. Ultra-Minimalistic Timeline Scroll Wheel
        MinimalTimelineScrollWheel(
            selectedHours = activeFilterHours,
            soundCount = soundDrops.size,
            graffitiCount = graffitiList.size,
            latestUploadTimestamp = latestUploadTimestamp,
            uploadMarkers = uploadMarkers,
            onHoursSelected = { viewModel.setTimelineFilter(it) }
        )

        // 2. Single Unified Google Map (with radar range rings, Sound-Drop spheres & AR pins)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(BlackCarbon)
        ) {
            SoundDropGoogleMap(
                userLat = userLat,
                userLng = userLng,
                soundDrops = soundDrops,
                graffitiList = graffitiList,
                playingDropId = playingDropId,
                selectedDrop = selectedDrop,
                pinnedDropTarget = pinnedDropTarget,
                hasLocationPermission = hasLocationPermission,
                distanceCalculator = { lat1, lng1, lat2, lng2 ->
                    viewModel.getDistanceInMeters(lat1, lng1, lat2, lng2)
                },
                onSoundDropSelected = { drop ->
                    selectedDrop = drop
                    pinnedDropTarget = null
                },
                onMapCoordinateTapped = { latLng ->
                    pinnedDropTarget = latLng
                },
                onClearPinnedTarget = {
                    pinnedDropTarget = null
                },
                onDropAtPinnedTarget = {
                    showDropSheet = true
                },
                onRecenterToUser = {
                    if (hasLocationPermission) {
                        viewModel.syncRealGpsLocation()
                    }
                },
                onToggleSoundDropPlayback = { drop ->
                    viewModel.toggleSoundDropPlayback(drop)
                },
                onSimulateWalkToDrop = { drop ->
                    viewModel.setUserCoordinates(drop.latitude, drop.longitude)
                },
                onDeleteSoundDrop = { drop ->
                    viewModel.deleteSoundDrop(drop)
                    selectedDrop = null
                },
                onClearSelectedDrop = {
                    selectedDrop = null
                },
                activeGeohashTile = activeGeohashTile,
                communityCount = communityCount
            )

            GridDiagnosticHeader(
                lat = userLat,
                lng = userLng,
                locationState = locationState,
                isLiveGpsActive = isLiveGpsActive
            )

            if (!dismissLocationBanner && locationState.status != LocationPermissionStatus.GRANTED_PRECISE) {
                LocationPermissionCard(
                    permissionState = locationState,
                    onRequestLocation = { locationPermissionController.requestPermission() },
                    onOpenSettings = { locationPermissionController.openAppSettings() },
                    onDismiss = { dismissLocationBanner = true },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(start = 12.dp, end = 12.dp, top = 92.dp)
                )
            }

            if (selectedDrop == null) {
                SimulationDpad(
                    viewModel = viewModel,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(8.dp)
                )
            }

            FloatingActionButton(
                onClick = { showDropSheet = true },
                containerColor = GuerrillaPink,
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("drop_sound_fab")
            ) {
                Icon(Icons.Default.Mic, contentDescription = "Record & drop new Sound Drop")
            }
        }

        // 3. Reachable Sound-Drops Feed
        NearbyFeedsPanel(viewModel)
    }

    if (showDropSheet) {
        DropSoundDialog(
            viewModel = viewModel,
            hasAudioPermission = hasAudioPermission,
            onRequestAudioPermission = onRequestAudioPermission,
            targetLat = pinnedDropTarget?.latitude,
            targetLng = pinnedDropTarget?.longitude,
            onDismiss = {
                showDropSheet = false
                pinnedDropTarget = null
            }
        )
    }
}

@Composable
fun GridDiagnosticHeader(
    lat: Double,
    lng: Double,
    locationState: LocationPermissionState,
    isLiveGpsActive: Boolean
) {
    val gpsLabel = when {
        !locationState.hasAnyLocation -> "GPS: PERMISSION DENIED (SIM)"
        !isLiveGpsActive -> "GPS: SIMULATOR OVERRIDE"
        locationState.hasFineLocation -> "GPS: LIVE PRECISE"
        else -> "GPS: LIVE APPROXIMATE"
    }
    val statusColor = if (locationState.hasAnyLocation && isLiveGpsActive) AcidGreen else WarningYellow

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(1.dp, LaserCyan.copy(alpha = 0.3f)),
        modifier = Modifier
            .padding(12.dp)
            .widthIn(max = 225.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "2ND GEOFENCING CORE: ACTIVE",
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AcidGreen,
                        letterSpacing = 1.2.sp
                    )
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = gpsLabel,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = statusColor.copy(alpha = 0.9f)
                )
            )
            Text(
                text = String.format("LAT: %.6f | LNG: %.6f", lat, lng),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = LaserCyan,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }
}

@Composable
fun SimulationDpad(viewModel: MainViewModel, modifier: Modifier = Modifier) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.85f)),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MutedText.copy(alpha = 0.2f)),
        modifier = modifier
            .padding(16.dp)
            .width(130.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(8.dp)
        ) {
            Text(
                text = "GPS SIMULATOR",
                style = MaterialTheme.typography.labelSmall.copy(
                    fontFamily = FontFamily.Monospace,
                    color = WarningYellow,
                    fontSize = 8.sp,
                    letterSpacing = 1.sp
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            
            // Layout N/S/W/E
            IconButton(
                onClick = { viewModel.moveUser(0.0004, 0.0) }, // Move North
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.ArrowDropUp, contentDescription = "Simulate walk North", tint = LaserCyan)
            }
            Row(
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                IconButton(
                    onClick = { viewModel.moveUser(0.0, -0.0005) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowLeft, contentDescription = "Simulate walk West", tint = LaserCyan)
                }
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(Color.Transparent)
                )
                IconButton(
                    onClick = { viewModel.moveUser(0.0, 0.0005) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowRight, contentDescription = "Simulate walk East", tint = LaserCyan)
                }
            }
            IconButton(
                onClick = { viewModel.moveUser(-0.0004, 0.0) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Simulate walk South", tint = LaserCyan)
            }
        }
    }
}

@Composable
fun NearbyFeedsPanel(viewModel: MainViewModel) {
    val userLat by viewModel.userLatitude.collectAsState()
    val userLng by viewModel.userLongitude.collectAsState()
    val soundDrops by viewModel.soundDropsList.collectAsState()

    // Filter sound drops the user is physically inside of
    val reachableDrops = remember(userLat, userLng, soundDrops) {
        soundDrops.filter { drop ->
            val dist = viewModel.getDistanceInMeters(userLat, userLng, drop.latitude, drop.longitude)
            dist <= drop.radiusInMeters
        }
    }

    val playingId by viewModel.playingDropId.collectAsState()
    val playbackProg by viewModel.playbackProgress.collectAsState()

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        border = BorderStroke(1.dp, MutedText.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "REACHABLE SOUND-DROP FEED",
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = AcidGreen,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(AcidGreen.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "${reachableDrops.size} SPHERES IN RANGE",
                        color = AcidGreen,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (reachableDrops.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .border(1.dp, MutedText.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "🏴‍☠️ SPATIAL CHANNELS EMPTY",
                            color = MutedText,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Walk closer to red transmitter circles, or place a new audio beacon.",
                            textAlign = TextAlign.Center,
                            color = MutedText.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(reachableDrops) { drop ->
                        val isPlaying = playingId == drop.id
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = LightGreyCarbon),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .border(
                                    1.dp,
                                    if (isPlaying) AcidGreen.copy(alpha = 0.5f) else Color.Transparent,
                                    RoundedCornerShape(8.dp)
                                ),
                            headlineContent = {
                                Text(
                                    text = drop.title,
                                    style = MaterialTheme.typography.titleSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = WhiteText
                                    )
                                )
                            },
                            supportingContent = {
                                Column {
                                    val originTag = if (drop.isFromCommunity) "PUBLIC" else "OWN"
                                    val sourceTag = if (!drop.audioUrl.isNullOrBlank()) "STREAM" else "LOCAL"
                                    Text(
                                        text = "${drop.description} [R:${drop.radiusInMeters.toInt()}m] • $originTag • $sourceTag • ${drop.dspPreset} • ${drop.creatorName} • ${formatUploadTimestamp(drop.timestamp)}",
                                        fontSize = 11.sp,
                                        color = MutedText,
                                        fontFamily = FontFamily.Monospace
                                    )
                                    if (isPlaying) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        LinearProgressIndicator(
                                            progress = { playbackProg },
                                            color = AcidGreen,
                                            trackColor = BlackCarbon,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                }
                            },
                            trailingContent = {
                                IconButton(
                                    onClick = { viewModel.toggleSoundDropPlayback(drop) },
                                    modifier = Modifier.background(
                                        if (isPlaying) AcidGreen else BlackCarbon, CircleShape
                                    )
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = "Trigger play simulation for audio beacon",
                                        tint = if (isPlaying) Color.Black else AcidGreen
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------
// REAL MICROPHONE AUDIO RECORDER & PREVIEW DIALOG
// ----------------------------------------------------
@Composable
fun DropSoundDialog(
    viewModel: MainViewModel,
    hasAudioPermission: Boolean,
    onRequestAudioPermission: () -> Unit,
    targetLat: Double? = null,
    targetLng: Double? = null,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var radius by remember { mutableStateOf(150f) }
    var isAnonymous by remember { mutableStateOf(false) }
    var publishToCommunity by remember { mutableStateOf(true) }

    val isRecording by viewModel.isRecordingAudio.collectAsState()
    val ampPoints by viewModel.recordedAmplitudePoints.collectAsState()
    val recordDuration by viewModel.recordingDuration.collectAsState()
    val pendingAudioPath by viewModel.pendingRecordedAudioPath.collectAsState()
    val selectedVoicePreset by viewModel.selectedVoicePreset.collectAsState()
    val isPreviewPlaying by viewModel.isPreviewPlaying.collectAsState()
    val isUsingHardwareMic by viewModel.isUsingHardwareMic.collectAsState()
    val currentUserLat by viewModel.userLatitude.collectAsState()
    val currentUserLng by viewModel.userLongitude.collectAsState()
    val effectiveLat = targetLat ?: currentUserLat
    val effectiveLng = targetLng ?: currentUserLng

    AlertDialog(
        onDismissRequest = {
            if (!isRecording) {
                viewModel.discardRecording()
                onDismiss()
            }
        },
        containerColor = DarkGreyCarbon,
        title = {
            Text(
                text = "RECORD & DROP AUDIO BEACON",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = GuerrillaPink,
                    letterSpacing = 1.2.sp
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BlackCarbon)
                        .border(1.dp, LaserCyan.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    Text(
                        text = if (targetLat != null && targetLng != null) {
                            String.format("PINNED MAP TARGET: %.5f, %.5f", effectiveLat, effectiveLng)
                        } else {
                            String.format("UNIT GPS TARGET: %.5f, %.5f", effectiveLat, effectiveLng)
                        },
                        color = LaserCyan,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (!hasAudioPermission) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(WarningYellow.copy(alpha = 0.14f))
                            .border(1.dp, WarningYellow)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "Mic permission needed for real audio capture.",
                            color = WarningYellow,
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(
                            onClick = onRequestAudioPermission,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "GRANT MIC",
                                color = AcidGreen,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("BEACON NAME / CALLSIGN") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GuerrillaPink,
                        focusedLabelColor = GuerrillaPink,
                        unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                        unfocusedLabelColor = MutedText
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("SPATIAL BROADCAST NOTE") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = GuerrillaPink,
                        focusedLabelColor = GuerrillaPink,
                        unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                        unfocusedLabelColor = MutedText
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "GEOFENCE RADIUS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MutedText
                        )
                        Text(
                            text = "${radius.toInt()} METERS",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = GuerrillaPink,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = radius,
                        onValueChange = { radius = it },
                        valueRange = 50f..1000f,
                        colors = SliderDefaults.colors(
                            thumbColor = GuerrillaPink,
                            activeTrackColor = GuerrillaPink,
                            inactiveTrackColor = BlackCarbon
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ANONYMOUS TRANSMISSION",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = WhiteText
                    )
                    Switch(
                        checked = isAnonymous,
                        onCheckedChange = { checked ->
                            isAnonymous = checked
                            if (checked && selectedVoicePreset == VoiceFilterPreset.RAW) {
                                viewModel.selectVoiceFilterPreset(VoiceFilterPreset.DEEP_ANON)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = GuerrillaPink,
                            checkedTrackColor = GuerrillaPink.copy(alpha = 0.3f),
                            uncheckedThumbColor = MutedText,
                            uncheckedTrackColor = BlackCarbon
                        )
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SHARE TO PUBLIC TILE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = LaserCyan
                    )
                    Switch(
                        checked = publishToCommunity,
                        onCheckedChange = { publishToCommunity = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LaserCyan,
                            checkedTrackColor = LaserCyan.copy(alpha = 0.3f),
                            uncheckedThumbColor = MutedText,
                            uncheckedTrackColor = BlackCarbon
                        )
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "VOICE MODULATION & LO-FI DSP",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = AcidGreen
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(VoiceFilterPreset.entries) { preset ->
                            val selected = selectedVoicePreset == preset
                            FilterChip(
                                selected = selected,
                                onClick = { viewModel.selectVoiceFilterPreset(preset) },
                                enabled = !isRecording,
                                label = {
                                    Text(
                                        text = preset.label,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AcidGreen.copy(alpha = 0.22f),
                                    selectedLabelColor = AcidGreen,
                                    containerColor = BlackCarbon,
                                    labelColor = MutedText
                                ),
                                modifier = Modifier.testTag("dsp_preset_${preset.id.lowercase()}")
                            )
                        }
                    }
                    Text(
                        text = selectedVoicePreset.description,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = MutedText
                    )
                }

                HorizontalDivider(color = BlackCarbon, thickness = 1.dp)

                // Real Microphone Audio Recorder & Waveform Panel
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BlackCarbon)
                        .border(1.dp, MutedText.copy(alpha = 0.2f), RoundedCornerShape(6.dp))
                        .padding(12.dp)
                ) {
                    val statusLabel = when {
                        isRecording && isUsingHardwareMic -> "MIC LIVE • ${recordDuration}s"
                        isRecording -> "CAPTURE SIM • ${recordDuration}s"
                        pendingAudioPath != null -> "RECORDED CLIP READY • ${recordDuration}s"
                        else -> "TAP RECORD TO CAPTURE AUDIO"
                    }
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            color = when {
                                isRecording -> AcidGreen
                                pendingAudioPath != null -> LaserCyan
                                else -> MutedText
                            },
                            fontWeight = FontWeight.Bold
                        )
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        if (ampPoints.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(MutedText.copy(alpha = 0.2f))
                            )
                        } else {
                            for (amp in ampPoints) {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(amp.coerceIn(0.1f, 1f))
                                        .background(if (isRecording) AcidGreen else GuerrillaPink)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            isRecording -> {
                                Button(
                                    onClick = { viewModel.stopRecordingCapture() },
                                    colors = ButtonDefaults.buttonColors(containerColor = AcidGreen),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.testTag("stop_recording_button")
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("STOP", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { viewModel.discardRecording() },
                                    border = BorderStroke(1.dp, Color.Red.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("DISCARD", color = Color.Red, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                            }
                            pendingAudioPath != null -> {
                                Button(
                                    onClick = { viewModel.togglePreviewRecording() },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isPreviewPlaying) GuerrillaPink else LaserCyan
                                    ),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.testTag("preview_recording_button")
                                ) {
                                    Icon(
                                        imageVector = if (isPreviewPlaying) Icons.Default.Stop else Icons.Default.PlayArrow,
                                        contentDescription = null,
                                        tint = Color.Black
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (isPreviewPlaying) "STOP" else "PREVIEW",
                                        color = Color.Black,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.discardRecording() },
                                    border = BorderStroke(1.dp, GuerrillaPink.copy(alpha = 0.7f)),
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = GuerrillaPink)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("CLEAR", color = GuerrillaPink, fontFamily = FontFamily.Monospace)
                                }
                            }
                            else -> {
                                Button(
                                    onClick = {
                                        if (!hasAudioPermission) {
                                            onRequestAudioPermission()
                                        }
                                        viewModel.startRecording()
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = GuerrillaPink),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.testTag("start_recording_button")
                                ) {
                                    Icon(Icons.Default.Mic, contentDescription = null, tint = Color.Black)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("RECORD", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.stopAndSaveRecording(
                        title = title,
                        description = description,
                        radiusInMeters = radius.toDouble(),
                        isAnonymous = isAnonymous,
                        publishToCommunity = publishToCommunity,
                        targetLat = effectiveLat,
                        targetLng = effectiveLng
                    )
                    onDismiss()
                },
                enabled = !isRecording,
                colors = ButtonDefaults.buttonColors(
                    containerColor = AcidGreen,
                    disabledContainerColor = MutedText.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.testTag("broadcast_drop_button")
            ) {
                Text(
                    "BROADCAST DROP",
                    color = if (isRecording) MutedText else Color.Black,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    viewModel.discardRecording()
                    onDismiss()
                },
                enabled = !isRecording,
                shape = RoundedCornerShape(4.dp)
            ) {
                Text("CANCEL", color = WhiteText, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

// ----------------------------------------------------
// GEO-LOCATED VIRTUAL AR SCANNERS SCREEN
// ----------------------------------------------------
@SuppressLint("ClickableViewAccessibility")
@Composable
fun ArScannerScreen(viewModel: MainViewModel, hasCameraPermission: Boolean) {
    val compassAzimuth by viewModel.deviceAzimuth.collectAsState()
    val devicePitch by viewModel.devicePitch.collectAsState()
    val graffitiList by viewModel.virtualGraffitiList.collectAsState()
    val userLat by viewModel.userLatitude.collectAsState()
    val userLng by viewModel.userLongitude.collectAsState()
    val arCoreState by viewModel.arCoreState.collectAsState()

    var showSprayDialog by remember { mutableStateOf(false) }
    var arCoreEngaged by remember { mutableStateOf(false) }
    var activePdfGraffiti by remember { mutableStateOf<VirtualGraffitiEntity?>(null) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }

    LaunchedEffect(Unit) {
        viewModel.checkArCoreAvailability()
    }

    ArSessionLifecycleEffect(
        sessionManager = viewModel.arSessionManager,
        enabled = arCoreEngaged && hasCameraPermission,
        destroyOnDispose = false
    )

    Box(modifier = Modifier.fillMaxSize()) {
        if (hasCameraPermission && arCoreEngaged) {
            ArCoreCameraSurface(
                sessionManager = viewModel.arSessionManager,
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("arcore_gl_surface")
            )
        } else if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = CameraPreview.Builder().build().also {
                                it.surfaceProvider = this.surfaceProvider
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(
                                    lifecycleOwner,
                                    cameraSelector,
                                    preview
                                )
                            } catch (e: Exception) {
                                // Camera creation error fallback
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Simulated visual static viewport
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(BlackCarbon)
                    .drawBehind {
                        // Background static visual texture
                        for (i in 0..50) {
                            val rX = (0..size.width.toInt()).random().toFloat()
                            val rY = (0..size.height.toInt()).random().toFloat()
                            drawCircle(
                                color = LaserCyan.copy(alpha = 0.08f),
                                radius = (1..3).random().dp.toPx(),
                                center = Offset(rX, rY)
                            )
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = LaserCyan.copy(alpha = 0.6f),
                        modifier = Modifier.size(54.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "VIRTUAL FEED SCANNER ONLINE",
                        color = LaserCyan,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.2.sp
                    )
                    Text(
                        text = "Grant Camera Permissions to align hardware viewfinder optics.",
                        color = MutedText,
                        fontSize = 11.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 40.dp)
                    )
                }
            }
        }

        // 1. Digital HUD Overlays (Compass + Pitch + Geospatial VPS Telemetry)
        ArHudOverlays(compassAzimuth, devicePitch, userLat, userLng, arCoreState.vps)

        // 2. Render Virtual AR Graffiti Overlays structurally (TEXT, IMAGE, PDF)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp)
        ) {
            for (graffiti in graffitiList) {
                var bearingDeg = Math.toDegrees(
                    kotlin.math.atan2(graffiti.longitude - userLng, graffiti.latitude - userLat)
                ).toFloat()
                if (bearingDeg < 0) bearingDeg += 360f

                var angleDelta = bearingDeg - compassAzimuth
                if (angleDelta > 180) angleDelta -= 360f
                if (angleDelta < -180) angleDelta += 360f

                val isVisibleInFov = angleDelta in -32f..32f
                val dist = viewModel.getDistanceInMeters(userLat, userLng, graffiti.latitude, graffiti.longitude)

                if (isVisibleInFov && dist <= 150.0) {
                    val screenWidthPercent = (angleDelta + 32f) / 64f
                    val xTranslation = screenWidthPercent - 0.5f
                    val scaleFactor = ((150f - dist.toFloat()).coerceIn(10f, 150f) / 150f) * graffiti.sizeScale
                    val opacityFactor = (150f - dist.toFloat()).coerceIn(0f, 150f) / 150f
                    val anchorKey = graffiti.publicId ?: graffiti.cloudId ?: "local_${graffiti.id}"
                    val isVpsLocked = viewModel.arSessionManager.isAnchorLockedInVps(anchorKey)
                    val accentColor = remember(graffiti.colorHex) {
                        runCatching { Color(android.graphics.Color.parseColor(graffiti.colorHex)) }
                            .getOrDefault(LaserCyan)
                    }
                    val mediaSource = graffiti.mediaUrl ?: graffiti.localMediaUri

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("ar_stencil_wrapper")
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.72f * opacityFactor)),
                            border = BorderStroke(
                                width = if (isVpsLocked) 2.dp else 1.dp,
                                color = (if (isVpsLocked) AcidGreen else accentColor).copy(alpha = (opacityFactor * 0.75f).coerceAtLeast(0.25f))
                            ),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(
                                    x = (xTranslation * 400).dp,
                                    y = (devicePitch * 4).dp
                                )
                                .widthIn(max = 220.dp)
                                .clickable(enabled = graffiti.mediaType == "PDF" && !mediaSource.isNullOrBlank()) {
                                    activePdfGraffiti = graffiti
                                }
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(10.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = graffiti.anchorType.replace("VPS_", ""),
                                        color = if (isVpsLocked) AcidGreen else LaserCyan,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                    if (isVpsLocked) {
                                        Text(
                                            text = "• VPS LOCKED",
                                            color = AcidGreen,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    if (graffiti.isFromCommunity) {
                                        Text(
                                            text = "• PUBLIC",
                                            color = WarningYellow,
                                            fontSize = 8.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }

                                if (graffiti.mediaType == "IMAGE" && !mediaSource.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    AsyncImage(
                                        model = mediaSource,
                                        contentDescription = "AR decal image: ${graffiti.contentText}",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .size(144.dp, 96.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                                    )
                                } else if (graffiti.mediaType == "PDF" && !mediaSource.isNullOrBlank()) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    PdfThumbnailPreview(
                                        source = mediaSource,
                                        cacheKey = anchorKey,
                                        viewModel = viewModel
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "TAP TO INSPECT PDF ZINE",
                                        color = WarningYellow,
                                        fontSize = 8.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = graffiti.contentText,
                                    color = accentColor,
                                    fontSize = (17 * scaleFactor).coerceIn(11f, 22f).sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    textAlign = TextAlign.Center,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = LaserCyan,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Text(
                                        text = "${dist.toInt()}M • ${graffiti.author} • ${formatUploadTimestamp(graffiti.timestamp).uppercase(Locale.getDefault())}",
                                        fontSize = 8.sp,
                                        color = LaserCyan,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Compass Emulator Adjustments panel (top-right side) overlaid for simple emulator tests
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.75f)),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(12.dp)
                .width(130.dp)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "HARDWARE GYRO PAN",
                    fontSize = 8.sp,
                    color = LaserCyan,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { viewModel.manualTurnCompass(-15f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ChevronLeft, "Pan screen view Left", tint = LaserCyan)
                    }
                    IconButton(
                        onClick = { viewModel.manualTurnCompass(15f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.ChevronRight, "Pan screen view Right", tint = LaserCyan)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { viewModel.manualTiltPitch(4f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, "Tilt viewport Up", tint = LaserCyan)
                    }
                    IconButton(
                        onClick = { viewModel.manualTiltPitch(-4f) },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Tilt viewport Down", tint = LaserCyan)
                    }
                }
            }
        }

        // 4. ARCore Session Manager HUD Card (bottom-left)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.88f)),
            border = BorderStroke(
                1.dp,
                when {
                    arCoreState.isSessionResumed -> AcidGreen.copy(alpha = 0.7f)
                    arCoreState.availability == ArCoreAvailabilityStatus.SUPPORTED_INSTALLED -> LaserCyan.copy(alpha = 0.6f)
                    else -> WarningYellow.copy(alpha = 0.45f)
                }
            ),
            shape = RoundedCornerShape(6.dp),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 12.dp, bottom = 16.dp, end = 84.dp)
                .widthIn(max = 260.dp)
                .testTag("arcore_session_card")
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "ARCORE: ${arCoreState.trackingState}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = if (arCoreState.isSessionResumed) AcidGreen else LaserCyan
                    )
                    Text(
                        text = "PLANES ${arCoreState.detectedPlanesCount} | ANCHORS ${arCoreState.activeAnchorsCount}",
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MutedText
                    )
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "VPS GEO: ${arCoreState.vps.earthTrackingState} | ${arCoreState.vps.resolvedVpsAnchorsCount} LOCKED",
                    fontSize = 9.sp,
                    color = if (arCoreState.vps.earthTrackingState == "TRACKING") AcidGreen else WarningYellow,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = arCoreState.statusMessage,
                    fontSize = 9.sp,
                    color = WhiteText.copy(alpha = 0.85f),
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 12.sp
                )
                if (hasCameraPermission && activity != null &&
                    arCoreState.availability != ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE
                ) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (arCoreEngaged) {
                                viewModel.pauseArCoreSession()
                                arCoreEngaged = false
                            } else {
                                try {
                                    ProcessCameraProvider.getInstance(context).get().unbindAll()
                                } catch (_: Throwable) {
                                }
                                arCoreEngaged = true
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (arCoreEngaged) GuerrillaPink else LaserCyan
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier
                            .height(32.dp)
                            .testTag("arcore_toggle_button")
                    ) {
                        Text(
                            text = if (arCoreEngaged) "STOP ARCORE SESSION" else "INIT ARCORE SESSION",
                            color = Color.Black,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 5. Action FAB to spray a new stencil
        FloatingActionButton(
            onClick = { showSprayDialog = true },
            containerColor = LaserCyan,
            contentColor = Color.Black,
            shape = CircleShape,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
                .testTag("spray_graffiti_fab")
        ) {
            Icon(Icons.Default.Add, contentDescription = "Establish physical spatial graffiti anchor")
        }
    }

    if (showSprayDialog) {
        StencilGraffitiDialog(
            viewModel = viewModel,
            onDismiss = { showSprayDialog = false }
        )
    }

    activePdfGraffiti?.let { pdfGraffiti ->
        PdfStencilViewerDialog(
            graffiti = pdfGraffiti,
            viewModel = viewModel,
            onDismiss = { activePdfGraffiti = null }
        )
    }
}

@Composable
fun ArHudOverlays(
    azimuth: Float,
    pitch: Float,
    lat: Double,
    lng: Double,
    vps: GeospatialVpsTelemetry
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .border(2.dp, LaserCyan.copy(alpha = 0.25f))
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp)
        ) {
            Text(
                text = "SCAN TARGET: PHYSICAL MASONRY DEVIATION",
                color = LaserCyan,
                fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = if (vps.earthTrackingState == "TRACKING") {
                    "VPS TRACKING ±${String.format("%.1f", vps.horizontalAccuracyM)}M"
                } else {
                    "VPS: ${vps.vpsAvailability}"
                },
                color = if (vps.earthTrackingState == "TRACKING") AcidGreen else WarningYellow,
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }

        // Top-centered Compass indicator tape
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 16.dp)
                .background(Color.Black.copy(alpha = 0.7f))
                .border(1.dp, LaserCyan.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text(
                text = "HEADING BEARING",
                fontSize = 8.sp,
                color = MutedText,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "${azimuth.toInt()}° | " + getIntercardinalDirection(azimuth),
                fontSize = 14.sp,
                color = LaserCyan,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        }

        // Left-side pitch elevation indicator tape
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 16.dp)
                .background(Color.Black.copy(alpha = 0.6f))
                .border(1.dp, LaserCyan.copy(alpha = 0.3f))
                .padding(6.dp)
        ) {
            Text(
                text = String.format("PITCH\n%+d°\nLAT %.3f", pitch.toInt(), lat),
                fontSize = 9.sp,
                color = LaserCyan,
                fontFamily = FontFamily.Monospace,
                textAlign = TextAlign.Center
            )
        }

        // Center crosshair
        Canvas(
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.Center)
        ) {
            val c = size.width / 2f
            drawCircle(
                color = LaserCyan.copy(alpha = 0.4f),
                radius = 12.dp.toPx(),
                style = Stroke(width = 1.dp.toPx())
            )
            drawLine(
                color = LaserCyan,
                start = Offset(c - 8.dp.toPx(), c),
                end = Offset(c + 8.dp.toPx(), c),
                strokeWidth = 2.dp.toPx()
            )
            drawLine(
                color = LaserCyan,
                start = Offset(c, c - 8.dp.toPx()),
                end = Offset(c, c + 8.dp.toPx()),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
fun PdfThumbnailPreview(
    source: String,
    cacheKey: String,
    viewModel: MainViewModel
) {
    var thumbBitmap by remember(source, cacheKey) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(source, cacheKey) {
        thumbBitmap = withContext(Dispatchers.IO) {
            val cached = viewModel.preparePdfForViewing(source, cacheKey) ?: return@withContext null
            renderPdfPageBitmap(cached, pageIndex = 0, targetWidthPx = 280)
        }
    }

    if (thumbBitmap != null) {
        Image(
            bitmap = thumbBitmap!!.asImageBitmap(),
            contentDescription = "AR pinned PDF page preview",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier
                .size(120.dp, 150.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, WarningYellow.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
        )
    } else {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(120.dp, 72.dp)
                .background(DarkGreyCarbon)
                .border(1.dp, WarningYellow.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
        ) {
            Icon(
                imageVector = Icons.Default.PictureAsPdf,
                contentDescription = "PDF AR stencil",
                tint = WarningYellow,
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
fun PdfStencilViewerDialog(
    graffiti: VirtualGraffitiEntity,
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    val source = graffiti.mediaUrl ?: graffiti.localMediaUri ?: ""
    val cacheKey = graffiti.publicId ?: graffiti.cloudId ?: "local_${graffiti.id}"
    var cachedFile by remember(source) { mutableStateOf<File?>(null) }
    var pageIndex by remember { mutableStateOf(0) }
    var pageCount by remember { mutableStateOf(0) }
    var renderedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(source) {
        if (source.isNotBlank()) {
            val file = viewModel.preparePdfForViewing(source, cacheKey)
            cachedFile = file
            if (file != null) {
                withContext(Dispatchers.IO) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    PdfRenderer(pfd).use { renderer ->
                        pageCount = renderer.pageCount
                    }
                    pfd.close()
                }
            }
        }
    }

    LaunchedEffect(cachedFile, pageIndex) {
        val file = cachedFile ?: return@LaunchedEffect
        renderedBitmap = withContext(Dispatchers.IO) {
            renderPdfPageBitmap(file, pageIndex = pageIndex, targetWidthPx = 720)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkGreyCarbon,
        title = {
            Text(
                text = "AR PDF: ${graffiti.contentText}",
                color = WarningYellow,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val bmp = renderedBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "Rendered PDF stencil page ${pageIndex + 1}",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 240.dp, max = 380.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color.White)
                    )
                } else {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp)
                            .background(BlackCarbon)
                    ) {
                        CircularProgressIndicator(color = LaserCyan)
                    }
                }
                if (pageCount > 1) {
                    Row(
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedButton(
                            onClick = { if (pageIndex > 0) pageIndex-- },
                            enabled = pageIndex > 0
                        ) {
                            Text("PREV", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        Text(
                            text = "PAGE ${pageIndex + 1} / $pageCount",
                            color = LaserCyan,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp
                        )
                        OutlinedButton(
                            onClick = { if (pageIndex < pageCount - 1) pageIndex++ },
                            enabled = pageIndex < pageCount - 1
                        ) {
                            Text("NEXT", fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", color = AcidGreen, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    )
}

private fun renderPdfPageBitmap(file: File, pageIndex: Int, targetWidthPx: Int): Bitmap? {
    return runCatching {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                if (renderer.pageCount == 0) return null
                val safeIndex = pageIndex.coerceIn(0, renderer.pageCount - 1)
                renderer.openPage(safeIndex).use { page ->
                    val aspect = page.height.toFloat() / page.width.coerceAtLeast(1).toFloat()
                    val width = targetWidthPx.coerceAtLeast(120)
                    val height = (width * aspect).toInt().coerceAtLeast(120)
                    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(android.graphics.Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    bitmap
                }
            }
        }
    }.getOrNull()
}

fun getIntercardinalDirection(azimuth: Float): String {
    return when (azimuth) {
        in 337.5f..360f, in 0f..22.5f -> "NORTH (N)"
        in 22.5f..67.5f -> "NORTHEAST (NE)"
        in 67.5f..112.5f -> "EAST (E)"
        in 112.5f..157.5f -> "SOUTHEAST (SE)"
        in 157.5f..202.5f -> "SOUTH (S)"
        in 202.5f..247.5f -> "SOUTHWEST (SW)"
        in 247.5f..292.5f -> "WEST (W)"
        else -> "NORTHWEST (NW)"
    }
}

@Composable
fun StencilGraffitiDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf("") }
    var crewTag by remember { mutableStateOf("") }
    var selectedColorIdx by remember { mutableStateOf(0) }
    var scaleFactor by remember { mutableStateOf(1.0f) }
    var mediaType by remember { mutableStateOf("TEXT") }
    var selectedMediaUri by remember { mutableStateOf<Uri?>(null) }
    var anchorMode by remember { mutableStateOf(VpsAnchorSurfaceMode.VPS_ROOFTOP) }
    var publishToCommunity by remember { mutableStateOf(true) }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            selectedMediaUri = uri
            mediaType = "IMAGE"
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            selectedMediaUri = uri
            mediaType = "PDF"
        }
    }

    val colors = listOf(
        "#FF0055" to "Guerrilla Pink",
        "#00FF55" to "Acid Green",
        "#00D2FF" to "Laser Cyan",
        "#FFFF00" to "Hazmat Yellow"
    )

    val vpsModes = listOf(
        VpsAnchorSurfaceMode.VPS_ROOFTOP,
        VpsAnchorSurfaceMode.VPS_TERRAIN,
        VpsAnchorSurfaceMode.VPS_WGS84
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = DarkGreyCarbon,
        title = {
            Text(
                text = "SPRAY AR / VPS STENCIL",
                style = MaterialTheme.typography.titleMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = LaserCyan,
                    letterSpacing = 1.5.sp
                )
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "STENCIL PAYLOAD TYPE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MutedText
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("TEXT", "IMAGE", "PDF").forEach { mode ->
                            FilterChip(
                                selected = mediaType == mode,
                                onClick = {
                                    mediaType = mode
                                    if (mode == "TEXT") selectedMediaUri = null
                                },
                                label = {
                                    Text(
                                        text = mode,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = LaserCyan.copy(alpha = 0.22f),
                                    selectedLabelColor = LaserCyan,
                                    containerColor = BlackCarbon,
                                    labelColor = MutedText
                                ),
                                modifier = Modifier.testTag("graffiti_media_${mode.lowercase()}")
                            )
                        }
                    }
                    if (mediaType == "IMAGE") {
                        OutlinedButton(
                            onClick = {
                                imagePickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            border = BorderStroke(1.dp, AcidGreen),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pick_graffiti_image_button")
                        ) {
                            Icon(Icons.Default.Image, contentDescription = null, tint = AcidGreen, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedMediaUri != null) "CHANGE STENCIL IMAGE" else "SELECT IMAGE (PHOTO PICKER)",
                                color = AcidGreen,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (mediaType == "PDF") {
                        OutlinedButton(
                            onClick = {
                                pdfPickerLauncher.launch(arrayOf("application/pdf"))
                            },
                            border = BorderStroke(1.dp, WarningYellow),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("pick_graffiti_pdf_button")
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, tint = WarningYellow, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (selectedMediaUri != null) "CHANGE PDF ZINE" else "ATTACH PDF DOCUMENT",
                                color = WarningYellow,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    if (selectedMediaUri != null && mediaType != "TEXT") {
                        Text(
                            text = "ATTACHED: ${selectedMediaUri?.lastPathSegment ?: "media_file"}",
                            color = AcidGreen,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("STENCIL CAPTION / MANIFESTO") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LaserCyan,
                        focusedLabelColor = LaserCyan,
                        unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                        unfocusedLabelColor = MutedText
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = crewTag,
                    onValueChange = { crewTag = it },
                    label = { Text("CREW / SIGNATURE TAG") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = LaserCyan,
                        focusedLabelColor = LaserCyan,
                        unfocusedBorderColor = MutedText.copy(alpha = 0.4f),
                        unfocusedLabelColor = MutedText
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "GEOSPATIAL VPS ANCHOR SURFACE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MutedText
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(vpsModes) { mode ->
                            FilterChip(
                                selected = anchorMode == mode,
                                onClick = { anchorMode = mode },
                                label = {
                                    Text(
                                        text = mode.label,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = AcidGreen.copy(alpha = 0.22f),
                                    selectedLabelColor = AcidGreen,
                                    containerColor = BlackCarbon,
                                    labelColor = MutedText
                                )
                            )
                        }
                    }
                }

                // Color selectors
                Column {
                    Text(
                        text = "NEON DYE SELECTOR",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MutedText,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        colors.forEachIndexed { idx, (hex, _) ->
                            val isSelected = selectedColorIdx == idx
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(Color(android.graphics.Color.parseColor(hex)))
                                    .border(
                                        width = 3.dp,
                                        color = if (isSelected) Color.White else Color.Transparent,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .clickable { selectedColorIdx = idx }
                            )
                        }
                    }
                }

                // Scale slider
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "STENCIL SIZE SCALE",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MutedText
                        )
                        Text(
                            text = String.format("%.1fx", scaleFactor),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = LaserCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Slider(
                        value = scaleFactor,
                        onValueChange = { scaleFactor = it },
                        valueRange = 0.5f..2.5f,
                        colors = SliderDefaults.colors(
                            thumbColor = LaserCyan,
                            activeTrackColor = LaserCyan,
                            inactiveTrackColor = BlackCarbon
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "SHARE TO PUBLIC TILE",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = AcidGreen
                    )
                    Switch(
                        checked = publishToCommunity,
                        onCheckedChange = { publishToCommunity = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AcidGreen,
                            checkedTrackColor = AcidGreen.copy(alpha = 0.3f),
                            uncheckedThumbColor = MutedText,
                            uncheckedTrackColor = BlackCarbon
                        )
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(BlackCarbon)
                        .border(1.dp, LaserCyan.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        text = "${anchorMode.description} • Bearing ${viewModel.deviceAzimuth.value.toInt()}° North",
                        fontSize = 10.sp,
                        color = MutedText,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    viewModel.dropVirtualGraffiti(
                        text = content,
                        author = crewTag,
                        hexColor = colors[selectedColorIdx].first,
                        scale = scaleFactor,
                        anchorMode = anchorMode,
                        mediaType = mediaType,
                        selectedMediaUri = selectedMediaUri,
                        publishToCommunity = publishToCommunity
                    )
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = LaserCyan),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.testTag("apply_stencil_button")
            ) {
                Text("APPLY STENCIL", color = Color.Black, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("CANCEL", color = WhiteText, fontFamily = FontFamily.Monospace)
            }
        }
    )
}

// ----------------------------------------------------
// ARCHITECT BLUEPRINT & SPEC COMPANION LOG
// ----------------------------------------------------
@Composable
fun SolutionBlueprintScreen(viewModel: MainViewModel) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = LightGreyCarbon),
                border = BorderStroke(1.dp, GuerrillaPink.copy(alpha = 0.4f)),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Build, "Arch tool", tint = GuerrillaPink)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "2ND ARCHITECTURE HUD",
                            color = GuerrillaPink,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Solutions blueprint logs detailing geofencing coordinates, PostGIS projections, sound drops, and orientation sensor integrations.",
                        color = WhiteText.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { viewModel.wipeDatabase() },
                            colors = ButtonDefaults.buttonColors(containerColor = GuerrillaPink),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text("RESET MOCK DATABASE SEED", color = Color.Black, fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            BlueprintBlock(
                title = "1. GEOFENCING CORE (Location Tracking)",
                details = """
                    - System: Uses Google Play Services GeofencingProvider API alongside client-side distance filters.
                    - Mathematical Projection: Haversine geodesic trigonometric algorithms. Calculates dynamic radii (50m - 1000m) with earth ellipsoid coefficients.
                    - State: Maintained inside a Background Service paired with Foreground sticky persistent capabilities to remain active even when UI processes decouple.
                """.trimIndent()
            )
        }

        item {
            BlueprintBlock(
                title = "2. AR SCANNING (Anchor facade systems)",
                details = """
                    - Physics Engines: Merges CameraX/ARCore process loops with standard physical coordinate offsets.
                    - Compass Fusion: Leverages Sensor.TYPE_ROTATION_VECTOR to compute Azimuth (Yaw) and Pitch degrees in real-time.
                    - Facade Calculations: Determines projection intersections within a 64° Horizontal Field of View with active translation coefficients across distance indices.
                """.trimIndent()
            )
        }

        item {
            BlueprintBlock(
                title = "3. CLOUD SYNC & SCALABILITY (Postgis)",
                details = """
                    - Backend Option 1: Supabase/PostgreSQL with PostGIS extensions. Coordinates stored as GEOMETRY(Point, 4326). Queries resolve fast utilizing R-Tree spatial indexing.
                    - Backend Option 2: Firestore paired with Geohashes to handle bounding-box bounds checks.
                    - Binary storage: Audio files and PDF layouts persist inside Firebase Cloud Storage with dynamic content CDN links.
                """.trimIndent()
            )
        }
    }
}

@Composable
fun BlueprintBlock(title: String, details: String) {
    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon),
        border = BorderStroke(1.dp, LaserCyan.copy(alpha = 0.2f)),
        shape = RoundedCornerShape(4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(
                text = title,
                color = LaserCyan,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = details,
                color = WhiteText.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 16.sp
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

