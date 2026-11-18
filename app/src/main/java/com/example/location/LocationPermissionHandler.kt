package com.example.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ui.theme.DarkGreyCarbon
import com.example.ui.theme.LaserCyan
import com.example.ui.theme.MutedText
import com.example.ui.theme.WarningYellow
import com.example.ui.theme.WhiteText

enum class LocationPermissionStatus {
    NOT_REQUESTED,
    GRANTED_PRECISE,
    GRANTED_APPROXIMATE,
    RATIONALE_NEEDED,
    DENIED_PERMANENTLY
}

data class LocationPermissionState(
    val status: LocationPermissionStatus,
    val hasFineLocation: Boolean,
    val hasCoarseLocation: Boolean
) {
    val hasAnyLocation: Boolean
        get() = hasFineLocation || hasCoarseLocation
}

@Stable
class LocationPermissionController(
    val state: LocationPermissionState,
    private val onRequestPermission: () -> Unit,
    private val onOpenAppSettings: () -> Unit
) {
    fun requestPermission() = onRequestPermission()
    fun openAppSettings() = onOpenAppSettings()
}

fun evaluateLocationPermissionState(
    context: Context,
    hasAttemptedRequest: Boolean
): LocationPermissionState {
    val hasFine = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasCoarse = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val status = when {
        hasFine -> LocationPermissionStatus.GRANTED_PRECISE
        hasCoarse -> LocationPermissionStatus.GRANTED_APPROXIMATE
        !hasAttemptedRequest -> LocationPermissionStatus.NOT_REQUESTED
        else -> {
            val activity = context.findActivity()
            val showRationale = activity != null && (
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) || ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            if (showRationale) {
                LocationPermissionStatus.RATIONALE_NEEDED
            } else {
                LocationPermissionStatus.DENIED_PERMANENTLY
            }
        }
    }

    return LocationPermissionState(
        status = status,
        hasFineLocation = hasFine,
        hasCoarseLocation = hasCoarse
    )
}

/**
 * Standard Activity Result API controller for requesting and reacting to
 * foreground location permissions (`ACCESS_FINE_LOCATION` and `ACCESS_COARSE_LOCATION`).
 */
@Composable
fun rememberLocationPermissionController(
    onPermissionResult: (LocationPermissionState) -> Unit = {}
): LocationPermissionController {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnResult by rememberUpdatedState(onPermissionResult)

    var hasAttemptedRequest by remember { mutableStateOf(false) }
    var permissionState by remember {
        mutableStateOf(evaluateLocationPermissionState(context, hasAttemptedRequest = false))
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        hasAttemptedRequest = true
        val updated = evaluateLocationPermissionState(context, hasAttemptedRequest = true)
        permissionState = updated
        latestOnResult(updated)
    }

    // Re-evaluate when returning from Android App Settings
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val updated = evaluateLocationPermissionState(context, hasAttemptedRequest)
                if (updated != permissionState) {
                    permissionState = updated
                    latestOnResult(updated)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val openSettingsAction: () -> Unit = {
        val intent = Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", context.packageName, null)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    val requestAction: () -> Unit = {
        launcher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    return remember(permissionState) {
        LocationPermissionController(
            state = permissionState,
            onRequestPermission = requestAction,
            onOpenAppSettings = openSettingsAction
        )
    }
}

@Composable
fun LocationPermissionCard(
    permissionState: LocationPermissionState,
    onRequestLocation: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPermanentlyDenied = permissionState.status == LocationPermissionStatus.DENIED_PERMANENTLY
    val isApproximateOnly = permissionState.status == LocationPermissionStatus.GRANTED_APPROXIMATE

    val accentColor = if (isApproximateOnly) LaserCyan else WarningYellow
    val title = when (permissionState.status) {
        LocationPermissionStatus.GRANTED_APPROXIMATE -> "APPROXIMATE GPS ACTIVE"
        LocationPermissionStatus.DENIED_PERMANENTLY -> "GPS PERMISSION BLOCKED"
        LocationPermissionStatus.RATIONALE_NEEDED -> "LOCATION ACCESS NEEDED"
        LocationPermissionStatus.NOT_REQUESTED -> "ENABLE GPS FOR SOUND DROPS"
        LocationPermissionStatus.GRANTED_PRECISE -> "PRECISE GPS ACTIVE"
    }
    val body = when (permissionState.status) {
        LocationPermissionStatus.GRANTED_APPROXIMATE ->
            "Approximate location is active. Enable precise GPS to accurately trigger tight Sound Drop geofences."
        LocationPermissionStatus.DENIED_PERMANENTLY ->
            "Location permission was denied. Open App Settings to map live Sound Drops, or continue using the GPS Simulator."
        else ->
            "Grant location access to anchor geo-located Sound Drops to your real position and detect nearby spheres."
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkGreyCarbon.copy(alpha = 0.94f)),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.7f)),
        shape = RoundedCornerShape(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .testTag("location_permission_card")
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
                    Icon(
                        imageVector = if (isApproximateOnly) Icons.Default.GpsFixed else Icons.Default.LocationOff,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = title,
                        color = accentColor,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .size(32.dp)
                        .testTag("dismiss_location_banner_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss location permission prompt",
                        tint = MutedText,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = body,
                color = WhiteText.copy(alpha = 0.85f),
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = {
                    if (isPermanentlyDenied) onOpenSettings() else onRequestLocation()
                },
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .height(36.dp)
                    .testTag("grant_location_permission_button")
            ) {
                Text(
                    text = when {
                        isPermanentlyDenied -> "OPEN APP SETTINGS"
                        isApproximateOnly -> "UPGRADE TO PRECISE GPS"
                        else -> "ALLOW LOCATION"
                    },
                    color = Color.Black,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
