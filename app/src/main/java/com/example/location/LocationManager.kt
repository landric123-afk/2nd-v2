package com.example.location

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.example.data.database.SoundDropEntity
import com.example.data.database.VirtualGraffitiEntity
import com.example.service.NotificationHelper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingClient
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class GeofenceRegistrationStatus(
    val registeredCount: Int = 0,
    val usingPlayServicesGeofence: Boolean = false,
    val statusText: String = "STANDBY"
)

/**
 * LocationManager helper wrapping [FusedLocationProviderClient] and [GeofencingClient]
 * to stream coordinates, build circular geofences, and trigger local notifications when
 * entering a nearby Sound-Drop or Virtual Graffiti radius.
 */
class LocationManager(private val context: Context) {

    private val appContext = context.applicationContext
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(appContext)
    private val geofencingClient: GeofencingClient =
        LocationServices.getGeofencingClient(appContext)

    private var locationCallback: LocationCallback? = null

    private val enteredSoundDrops = mutableSetOf<String>()
    private val enteredGraffiti = mutableSetOf<String>()

    private val _geofenceStatus = MutableStateFlow(GeofenceRegistrationStatus())
    val geofenceStatus: StateFlow<GeofenceRegistrationStatus> = _geofenceStatus.asStateFlow()

    private val geofencePendingIntent: PendingIntent by lazy {
        val intent = Intent(appContext, GeofenceBroadcastReceiver::class.java)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        PendingIntent.getBroadcast(appContext, 2048, intent, flags)
    }

    fun hasLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return hasFine || hasCoarse
    }

    fun hasFineLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    @SuppressLint("MissingPermission")
    fun fetchLastKnownLocation(onLocation: (Double, Double) -> Unit) {
        if (!hasLocationPermission()) return
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    onLocation(loc.latitude, loc.longitude)
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Missing permission for lastLocation", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates(
        highAccuracy: Boolean = hasFineLocationPermission(),
        onLocationUpdate: (Double, Double) -> Unit
    ): Boolean {
        if (!hasLocationPermission()) {
            stopLocationUpdates()
            return false
        }
        if (locationCallback != null) return true

        val priority = if (highAccuracy && hasFineLocationPermission()) {
            Priority.PRIORITY_HIGH_ACCURACY
        } else {
            Priority.PRIORITY_BALANCED_POWER_ACCURACY
        }

        val request = LocationRequest.Builder(priority, 4000L)
            .setMinUpdateIntervalMillis(2000L)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc = result.lastLocation ?: return
                onLocationUpdate(loc.latitude, loc.longitude)
            }
        }

        locationCallback = callback
        return try {
            fusedLocationClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Failed to start FusedLocationProviderClient updates", e)
            stopLocationUpdates()
            false
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let { cb ->
            try {
                fusedLocationClient.removeLocationUpdates(cb)
            } catch (_: Throwable) {
            }
        }
        locationCallback = null
    }

    /**
     * Registers circular [Geofence] zones with [GeofencingClient] (up to 95 zones per Android limit)
     * and keeps [GeofenceRegistry] synchronized for local notification dispatch.
     */
    @SuppressLint("MissingPermission")
    fun syncCircularGeofences(
        soundDrops: List<SoundDropEntity>,
        graffitiList: List<VirtualGraffitiEntity>
    ) {
        val geofences = mutableListOf<Geofence>()
        val metadata = mutableListOf<RegisteredGeofenceMeta>()

        soundDrops.take(65).forEach { drop ->
            val key = "drop_${drop.cloudId ?: drop.id}"
            val clampedRadius = drop.radiusInMeters.toFloat().coerceIn(50f, 1000f)
            geofences += Geofence.Builder()
                .setRequestId(key)
                .setCircularRegion(drop.latitude, drop.longitude, clampedRadius)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
            metadata += RegisteredGeofenceMeta(
                requestId = key,
                title = drop.title,
                notificationBody = "Entered \"${drop.title}\" (${clampedRadius.toInt()}m sphere) by ${drop.creatorName}. Tap to listen.",
                notificationId = key.hashCode(),
                isGraffiti = false
            )
        }

        graffitiList.take(30).forEach { g ->
            val key = "graf_${g.cloudId ?: g.id}"
            geofences += Geofence.Builder()
                .setRequestId(key)
                .setCircularRegion(g.latitude, g.longitude, 100f)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER)
                .build()
            metadata += RegisteredGeofenceMeta(
                requestId = key,
                title = g.contentText,
                notificationBody = "Virtual AR stencil nearby: \"${g.contentText}\". Open AR Scanner to align.",
                notificationId = key.hashCode(),
                isGraffiti = true
            )
        }

        GeofenceRegistry.replaceAll(metadata)

        if (!hasFineLocationPermission() || geofences.isEmpty()) {
            _geofenceStatus.value = GeofenceRegistrationStatus(
                registeredCount = metadata.size,
                usingPlayServicesGeofence = false,
                statusText = if (geofences.isEmpty()) "0 ZONES" else "FUSED PROXIMITY (${metadata.size} ZONES)"
            )
            return
        }

        val geofencingRequest = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()

        geofencingClient.removeGeofences(geofencePendingIntent).addOnCompleteListener {
            try {
                geofencingClient.addGeofences(geofencingRequest, geofencePendingIntent)
                    .addOnSuccessListener {
                        _geofenceStatus.value = GeofenceRegistrationStatus(
                            registeredCount = geofences.size,
                            usingPlayServicesGeofence = true,
                            statusText = "CIRCULAR GEOFENCE (${geofences.size} ACTIVE)"
                        )
                    }
                    .addOnFailureListener { e ->
                        Log.w(TAG, "Play Services Geofencing fallback to Fused proximity: ${e.message}")
                        _geofenceStatus.value = GeofenceRegistrationStatus(
                            registeredCount = metadata.size,
                            usingPlayServicesGeofence = false,
                            statusText = "FUSED PROXIMITY (${metadata.size} ZONES)"
                        )
                    }
            } catch (e: SecurityException) {
                Log.w(TAG, "Missing permission for addGeofences", e)
            }
        }
    }

    /**
     * Evaluates circular radius entry using live [FusedLocationProviderClient] or simulated coordinates
     * and dispatches local notifications on boundary entry.
     */
    fun evaluateCircularGeofences(
        lat: Double,
        lng: Double,
        soundDrops: List<SoundDropEntity>,
        graffitiList: List<VirtualGraffitiEntity>
    ) {
        NotificationHelper.createNotificationChannel(appContext)

        for (drop in soundDrops) {
            val key = "drop_${drop.cloudId ?: drop.id}"
            val dist = distanceMeters(lat, lng, drop.latitude, drop.longitude)
            if (dist <= drop.radiusInMeters) {
                if (enteredSoundDrops.add(key)) {
                    NotificationHelper.sendGeofenceBreachNotification(
                        context = appContext,
                        title = "SOUND-DROP IN RANGE",
                        body = "Entered \"${drop.title}\" (${dist.toInt()}m away • ${drop.radiusInMeters.toInt()}m sphere). Tap to intercept.",
                        soundDropId = key.hashCode(),
                        isGraffiti = false
                    )
                }
            } else {
                enteredSoundDrops.remove(key)
            }
        }

        for (g in graffitiList) {
            val key = "graf_${g.cloudId ?: g.id}"
            val dist = distanceMeters(lat, lng, g.latitude, g.longitude)
            if (dist <= 100.0) {
                if (enteredGraffiti.add(key)) {
                    NotificationHelper.sendGeofenceBreachNotification(
                        context = appContext,
                        title = "VISUAL ANCHOR LINKED",
                        body = "Virtual graffiti (${dist.toInt()}m): \"${g.contentText}\". Open AR Scanner to align.",
                        soundDropId = key.hashCode(),
                        isGraffiti = true
                    )
                }
            } else {
                enteredGraffiti.remove(key)
            }
        }
    }

    fun resetBreachHistory() {
        enteredSoundDrops.clear()
        enteredGraffiti.clear()
    }

    fun release() {
        stopLocationUpdates()
    }

    companion object {
        private const val TAG = "SecondLocationManager"

        fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val earthRadius = 6371000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }
    }
}
