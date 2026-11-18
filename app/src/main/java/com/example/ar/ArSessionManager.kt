package com.example.ar

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.database.VirtualGraffitiEntity
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.VpsAvailability
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

enum class ArCoreAvailabilityStatus {
    UNKNOWN,
    CHECKING,
    SUPPORTED_INSTALLED,
    SUPPORTED_APK_TOO_OLD,
    SUPPORTED_NOT_INSTALLED,
    UNSUPPORTED_DEVICE
}

enum class VpsAnchorSurfaceMode(
    val id: String,
    val label: String,
    val description: String
) {
    VPS_ROOFTOP("VPS_ROOFTOP", "BUILDING FACADE / ROOF", "ARCore VPS Rooftop & Facade Anchor"),
    VPS_TERRAIN("VPS_TERRAIN", "STREET / TERRAIN", "ARCore VPS Terrain Surface Anchor"),
    VPS_WGS84("VPS_WGS84", "WGS84 GEO-LOCK", "Direct Latitude/Longitude/Altitude Anchor"),
    COMPASS_FALLBACK("COMPASS_FALLBACK", "GYRO BEARING", "Compass + Pitch Projection Fallback");

    companion object {
        fun fromId(raw: String?): VpsAnchorSurfaceMode =
            entries.firstOrNull { it.id == raw } ?: VPS_ROOFTOP
    }
}

data class GraffitiAnchorRecord(
    val id: String,
    val label: String,
    val anchor: Anchor,
    val anchorType: String = VpsAnchorSurfaceMode.VPS_WGS84.id
)

data class GeospatialVpsTelemetry(
    val isGeospatialSupported: Boolean = false,
    val earthState: String = "DISABLED",
    val earthTrackingState: String = "STOPPED",
    val vpsAvailability: String = "UNKNOWN",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val headingDeg: Double = 0.0,
    val horizontalAccuracyM: Double = 0.0,
    val verticalAccuracyM: Double = 0.0,
    val orientationAccuracyDeg: Double = 0.0,
    val resolvedVpsAnchorsCount: Int = 0
)

data class ArSessionState(
    val availability: ArCoreAvailabilityStatus = ArCoreAvailabilityStatus.UNKNOWN,
    val isSessionCreated: Boolean = false,
    val isSessionResumed: Boolean = false,
    val trackingState: String = "STANDBY",
    val detectedPlanesCount: Int = 0,
    val activeAnchorsCount: Int = 0,
    val frameTimestampNs: Long = 0L,
    val frameCount: Long = 0L,
    val lightIntensity: Float = 1.0f,
    val isDepthSupported: Boolean = false,
    val vps: GeospatialVpsTelemetry = GeospatialVpsTelemetry(),
    val statusMessage: String = "ARCore standby. Checking hardware support...",
    val errorMessage: String? = null
)

/**
 * Manages the Google ARCore [Session], OpenGL ES camera stream, Google Geospatial API ([Earth]),
 * persistent Terrain / Rooftop / WGS84 VPS anchors, and Compose lifecycle hooks.
 */
class ArSessionManager(
    private val context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Main)
) : DefaultLifecycleObserver {

    private val appContext: Context = context.applicationContext

    private val _sessionState = MutableStateFlow(ArSessionState())
    val sessionState: StateFlow<ArSessionState> = _sessionState.asStateFlow()

    private var session: Session? = null
    private var installRequested: Boolean = false
    private var availabilityJob: Job? = null
    private val trackedAnchors = mutableListOf<GraffitiAnchorRecord>()
    private val resolvedCloudAnchors = ConcurrentHashMap<String, GraffitiAnchorRecord>()
    private val pendingAnchorResolves = ConcurrentHashMap.newKeySet<String>()
    private var lastVpsCheckTileKey: String? = null

    // OpenGL camera background rendering state (accessed on GL thread)
    private var cameraTextureId: Int = -1
    private var shaderProgram: Int = 0
    private var positionAttrib: Int = -1
    private var texCoordAttrib: Int = -1
    private var textureUniform: Int = -1
    private var viewportWidth: Int = 0
    private var viewportHeight: Int = 0
    private var displayRotation: Int = Surface.ROTATION_0
    private var displayGeometryDirty: Boolean = false

    private val quadCoords: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(QUAD_COORDS)
            position(0)
        }

    private val quadTexCoordsTransformed: FloatBuffer = ByteBuffer
        .allocateDirect(QUAD_COORDS.size * 4)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()

    @Volatile
    private var latestFrame: Frame? = null

    fun checkAvailability() {
        availabilityJob?.cancel()
        availabilityJob = scope.launch {
            _sessionState.update {
                it.copy(
                    availability = ArCoreAvailabilityStatus.CHECKING,
                    statusMessage = "Probing Google Play Services for AR & Geospatial VPS...",
                    errorMessage = null
                )
            }

            if (!canProbeOrInstallArCore()) {
                _sessionState.update {
                    it.copy(
                        availability = ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE,
                        trackingState = "FALLBACK",
                        statusMessage = "Play Services for AR unavailable on this device/emulator. Gyro + VPS fallback active.",
                        errorMessage = null
                    )
                }
                return@launch
            }

            try {
                var availability = ArCoreApk.getInstance().checkAvailability(appContext)
                var attempts = 0
                while (availability.isTransient && attempts < 10) {
                    delay(200L)
                    availability = ArCoreApk.getInstance().checkAvailability(appContext)
                    attempts++
                }

                val mapped = mapAvailability(availability)
                val message = when (mapped) {
                    ArCoreAvailabilityStatus.SUPPORTED_INSTALLED ->
                        "ARCore + Geospatial VPS ready. Tap INIT ARCORE SESSION to localize."
                    ArCoreAvailabilityStatus.SUPPORTED_APK_TOO_OLD ->
                        "Google Play Services for AR needs an update."
                    ArCoreAvailabilityStatus.SUPPORTED_NOT_INSTALLED ->
                        "Google Play Services for AR can be installed to enable VPS anchors."
                    ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE ->
                        "ARCore unsupported on this hardware. Gyro spatial fallback active."
                    ArCoreAvailabilityStatus.CHECKING,
                    ArCoreAvailabilityStatus.UNKNOWN ->
                        "ARCore availability probe timed out. Gyro fallback active."
                }

                _sessionState.update {
                    it.copy(
                        availability = mapped,
                        statusMessage = message
                    )
                }
            } catch (t: Throwable) {
                _sessionState.update {
                    it.copy(
                        availability = ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE,
                        statusMessage = "ARCore unavailable in this runtime. Gyro spatial fallback active.",
                        errorMessage = t.message
                    )
                }
            }
        }
    }

    /**
     * Initializes the ARCore [Session] with horizontal/vertical planes, depth (when supported),
     * and Google Geospatial VPS (`Config.GeospatialMode.ENABLED`).
     */
    fun initializeSession(activity: Activity? = context.findActivity()): Boolean {
        if (!canProbeOrInstallArCore()) {
            _sessionState.update {
                it.copy(
                    availability = ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE,
                    isSessionCreated = false,
                    isSessionResumed = false,
                    trackingState = "FALLBACK",
                    statusMessage = "Play Services for AR unavailable on this device/emulator. Gyro fallback active.",
                    errorMessage = null
                )
            }
            return false
        }

        return try {
            if (session == null) {
                if (activity != null) {
                    val installStatus =
                        ArCoreApk.getInstance().requestInstall(activity, !installRequested)
                    when (installStatus) {
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                            installRequested = true
                            _sessionState.update {
                                it.copy(
                                    availability = ArCoreAvailabilityStatus.SUPPORTED_NOT_INSTALLED,
                                    statusMessage = "Installing Google Play Services for AR...",
                                    errorMessage = null
                                )
                            }
                            return false
                        }
                        ArCoreApk.InstallStatus.INSTALLED -> Unit
                    }
                }

                val newSession = Session(activity ?: appContext)
                val depthSupported = newSession.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                val geospatialSupported = newSession.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)

                val config = Config(newSession).apply {
                    planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
                    focusMode = Config.FocusMode.AUTO
                    lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
                    geospatialMode = if (geospatialSupported) Config.GeospatialMode.ENABLED else Config.GeospatialMode.DISABLED
                }
                newSession.configure(config)
                if (cameraTextureId != -1) {
                    newSession.setCameraTextureName(cameraTextureId)
                }
                if (viewportWidth > 0 && viewportHeight > 0) {
                    newSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                }
                session = newSession

                _sessionState.update {
                    it.copy(
                        availability = ArCoreAvailabilityStatus.SUPPORTED_INSTALLED,
                        isSessionCreated = true,
                        isDepthSupported = depthSupported,
                        trackingState = "INITIALIZING",
                        vps = it.vps.copy(isGeospatialSupported = geospatialSupported),
                        statusMessage = if (geospatialSupported) {
                            "ARCore + Geospatial VPS enabled. Point at nearby facades to localize."
                        } else {
                            "ARCore configured (H+V Planes; Geospatial VPS unsupported)."
                        },
                        errorMessage = null
                    )
                }
            }
            true
        } catch (e: UnavailableUserDeclinedInstallationException) {
            updateError(ArCoreAvailabilityStatus.SUPPORTED_NOT_INSTALLED, "User declined Play Services for AR.", e)
            false
        } catch (e: UnavailableArcoreNotInstalledException) {
            updateError(ArCoreAvailabilityStatus.SUPPORTED_NOT_INSTALLED, "Google Play Services for AR not installed.", e)
            false
        } catch (e: UnavailableApkTooOldException) {
            updateError(ArCoreAvailabilityStatus.SUPPORTED_APK_TOO_OLD, "Please update Google Play Services for AR.", e)
            false
        } catch (e: UnavailableSdkTooOldException) {
            updateError(ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE, "App update needed for ARCore SDK.", e)
            false
        } catch (e: UnavailableDeviceNotCompatibleException) {
            updateError(ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE, "Device is not ARCore certified. Gyro fallback active.", e)
            false
        } catch (t: Throwable) {
            updateError(
                ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE,
                "ARCore init failed: ${t.localizedMessage ?: "Unsupported runtime"}",
                t
            )
            false
        }
    }

    fun resumeSession(): Boolean {
        val activeSession = session ?: return false
        return try {
            activeSession.resume()
            _sessionState.update {
                it.copy(
                    isSessionResumed = true,
                    trackingState = "TRACKING_INIT",
                    statusMessage = "ARCore Session active. Aligning street-level VPS...",
                    errorMessage = null
                )
            }
            true
        } catch (e: CameraNotAvailableException) {
            _sessionState.update {
                it.copy(
                    isSessionResumed = false,
                    trackingState = "PAUSED",
                    statusMessage = "Camera unavailable for ARCore session.",
                    errorMessage = e.message
                )
            }
            false
        } catch (t: Throwable) {
            _sessionState.update {
                it.copy(
                    isSessionResumed = false,
                    trackingState = "ERROR",
                    statusMessage = "Unable to resume ARCore session.",
                    errorMessage = t.message
                )
            }
            false
        }
    }

    fun pauseSession() {
        try {
            session?.pause()
            _sessionState.update {
                it.copy(
                    isSessionResumed = false,
                    trackingState = if (session != null) "PAUSED" else "STANDBY",
                    statusMessage = if (session != null) "ARCore Session paused." else it.statusMessage
                )
            }
        } catch (_: Throwable) {
        }
    }

    override fun onResume(owner: LifecycleOwner) {
        if (session != null) resumeSession()
    }

    override fun onPause(owner: LifecycleOwner) {
        pauseSession()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        destroySession()
    }

    fun checkVpsAvailability(latitude: Double, longitude: Double) {
        val activeSession = session ?: return
        val tileKey = String.format("%.3f_%.3f", latitude, longitude)
        if (tileKey == lastVpsCheckTileKey) return
        lastVpsCheckTileKey = tileKey

        try {
            activeSession.checkVpsAvailabilityAsync(latitude, longitude) { availability ->
                val label = when (availability) {
                    VpsAvailability.AVAILABLE -> "VPS AVAILABLE"
                    VpsAvailability.UNAVAILABLE -> "VPS UNAVAILABLE (GPS FALLBACK)"
                    VpsAvailability.ERROR_NETWORK_CONNECTION -> "NETWORK ERROR"
                    VpsAvailability.ERROR_NOT_AUTHORIZED -> "KEY NOT AUTHORIZED"
                    VpsAvailability.ERROR_RESOURCE_EXHAUSTED -> "QUOTA EXHAUSTED"
                    else -> "UNKNOWN"
                }
                _sessionState.update { current ->
                    current.copy(vps = current.vps.copy(vpsAvailability = label))
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "VPS availability check skipped: ${t.message}")
        }
    }

    /**
     * Resolves an ARCore persistent Geospatial [Anchor] onto either Terrain, Rooftop/Building Facade,
     * or direct WGS84 coordinates so Virtual Graffiti locks onto real-world structures across devices.
     */
    fun createGeospatialAnchor(
        key: String,
        label: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        mode: VpsAnchorSurfaceMode
    ) {
        val activeSession = session ?: return
        val earth = activeSession.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) {
            createForwardAnchor(label)
            return
        }

        val eusQuat = try {
            earth.cameraGeospatialPose.eastUpSouthQuaternion
        } catch (_: Throwable) {
            floatArrayOf(0f, 0f, 0f, 1f)
        }
        val qx = eusQuat.getOrElse(0) { 0f }
        val qy = eusQuat.getOrElse(1) { 0f }
        val qz = eusQuat.getOrElse(2) { 0f }
        val qw = eusQuat.getOrElse(3) { 1f }

        when (mode) {
            VpsAnchorSurfaceMode.VPS_ROOFTOP -> {
                if (!pendingAnchorResolves.add(key)) return
                earth.resolveAnchorOnRooftopAsync(latitude, longitude, 0.25, qx, qy, qz, qw) { anchor, state ->
                    pendingAnchorResolves.remove(key)
                    if (state == Anchor.RooftopAnchorState.SUCCESS && anchor != null) {
                        val rec = GraffitiAnchorRecord(key, label, anchor, mode.id)
                        resolvedCloudAnchors[key] = rec
                        trackedAnchors.add(rec)
                        _sessionState.update { cur ->
                            cur.copy(
                                activeAnchorsCount = trackedAnchors.size,
                                vps = cur.vps.copy(resolvedVpsAnchorsCount = resolvedCloudAnchors.size),
                                statusMessage = "VPS Rooftop anchor locked: \"$label\""
                            )
                        }
                    } else {
                        Log.w(TAG, "Rooftop VPS resolve state=$state, falling back to WGS84 anchor")
                        createDirectWgs84Anchor(earth, key, label, latitude, longitude, altitudeMeters, qx, qy, qz, qw)
                    }
                }
            }
            VpsAnchorSurfaceMode.VPS_TERRAIN -> {
                if (!pendingAnchorResolves.add(key)) return
                earth.resolveAnchorOnTerrainAsync(latitude, longitude, 1.4, qx, qy, qz, qw) { anchor, state ->
                    pendingAnchorResolves.remove(key)
                    if (state == Anchor.TerrainAnchorState.SUCCESS && anchor != null) {
                        val rec = GraffitiAnchorRecord(key, label, anchor, mode.id)
                        resolvedCloudAnchors[key] = rec
                        trackedAnchors.add(rec)
                        _sessionState.update { cur ->
                            cur.copy(
                                activeAnchorsCount = trackedAnchors.size,
                                vps = cur.vps.copy(resolvedVpsAnchorsCount = resolvedCloudAnchors.size),
                                statusMessage = "VPS Terrain anchor locked: \"$label\""
                            )
                        }
                    } else {
                        createDirectWgs84Anchor(earth, key, label, latitude, longitude, altitudeMeters, qx, qy, qz, qw)
                    }
                }
            }
            VpsAnchorSurfaceMode.VPS_WGS84,
            VpsAnchorSurfaceMode.COMPASS_FALLBACK -> {
                createDirectWgs84Anchor(earth, key, label, latitude, longitude, altitudeMeters, qx, qy, qz, qw)
            }
        }
    }

    private fun createDirectWgs84Anchor(
        earth: Earth,
        key: String,
        label: String,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        qx: Float,
        qy: Float,
        qz: Float,
        qw: Float
    ) {
        try {
            val resolvedAlt = if (altitudeMeters != 0.0) altitudeMeters else earth.cameraGeospatialPose.altitude
            val anchor = earth.createAnchor(latitude, longitude, resolvedAlt, qx, qy, qz, qw)
            val rec = GraffitiAnchorRecord(key, label, anchor, VpsAnchorSurfaceMode.VPS_WGS84.id)
            resolvedCloudAnchors[key] = rec
            trackedAnchors.add(rec)
            _sessionState.update { cur ->
                cur.copy(
                    activeAnchorsCount = trackedAnchors.size,
                    vps = cur.vps.copy(resolvedVpsAnchorsCount = resolvedCloudAnchors.size)
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Direct WGS84 Earth anchor failed: ${t.message}")
        }
    }

    /**
     * Resolves nearby cloud/public Virtual Graffiti records into persistent ARCore VPS Anchors
     * once Google Geospatial [Earth] achieves [TrackingState.TRACKING].
     */
    fun syncGeospatialAnchors(graffitiList: List<VirtualGraffitiEntity>) {
        val activeSession = session ?: return
        val earth = activeSession.earth ?: return
        if (earth.trackingState != TrackingState.TRACKING) return

        graffitiList.take(20).forEach { graffiti ->
            val key = graffiti.publicId ?: graffiti.cloudId ?: "local_${graffiti.id}"
            if (resolvedCloudAnchors.containsKey(key) || pendingAnchorResolves.contains(key)) {
                return@forEach
            }
            val mode = VpsAnchorSurfaceMode.fromId(graffiti.anchorType)
            createGeospatialAnchor(
                key = key,
                label = graffiti.contentText,
                latitude = graffiti.latitude,
                longitude = graffiti.longitude,
                altitudeMeters = graffiti.altitudeMeters,
                mode = mode
            )
        }
    }

    fun isAnchorLockedInVps(key: String): Boolean {
        val rec = resolvedCloudAnchors[key] ?: return false
        return rec.anchor.trackingState == TrackingState.TRACKING
    }

    fun onSurfaceCreated() {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        cameraTextureId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)

        shaderProgram = buildCameraShaderProgram()
        positionAttrib = GLES20.glGetAttribLocation(shaderProgram, "a_Position")
        texCoordAttrib = GLES20.glGetAttribLocation(shaderProgram, "a_TexCoord")
        textureUniform = GLES20.glGetUniformLocation(shaderProgram, "sTexture")

        session?.let { activeSession ->
            try {
                activeSession.setCameraTextureName(cameraTextureId)
            } catch (_: Throwable) {
            }
        }
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportWidth = width
        viewportHeight = height
        displayRotation = resolveDisplayRotation()
        displayGeometryDirty = true
    }

    fun onDrawFrame(onFrameUpdate: ((Frame) -> Unit)? = null) {
        GLES20.glClearColor(0.05f, 0.05f, 0.06f, 1.0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val activeSession = session ?: return
        if (!_sessionState.value.isSessionResumed) return

        try {
            if (displayGeometryDirty && viewportWidth > 0 && viewportHeight > 0) {
                activeSession.setDisplayGeometry(displayRotation, viewportWidth, viewportHeight)
                displayGeometryDirty = false
            }

            val frame = activeSession.update()
            latestFrame = frame

            drawCameraBackground(frame)
            publishFrameTelemetry(activeSession, frame)
            onFrameUpdate?.invoke(frame)
        } catch (_: Throwable) {
        }
    }

    fun updateFrame(): Frame? {
        val activeSession = session ?: return null
        if (!_sessionState.value.isSessionResumed) return null
        return try {
            val frame = activeSession.update()
            latestFrame = frame
            publishFrameTelemetry(activeSession, frame)
            frame
        } catch (_: Throwable) {
            null
        }
    }

    private fun publishFrameTelemetry(activeSession: Session, frame: Frame) {
        val cameraTracking = frame.camera.trackingState
        val trackingLabel = when (cameraTracking) {
            TrackingState.TRACKING -> "TRACKING"
            TrackingState.PAUSED -> "PAUSED"
            TrackingState.STOPPED -> "STOPPED"
        }
        val planeCount = activeSession.getAllTrackables(Plane::class.java)
            .count { it.trackingState == TrackingState.TRACKING }
        val activeAnchors = trackedAnchors.count { it.anchor.trackingState != TrackingState.STOPPED }
        val lightPixelIntensity = frame.lightEstimate.pixelIntensity

        val earth = activeSession.earth
        val vpsTelemetry = if (earth != null) {
            val earthStateLabel = earth.earthState.name
            val earthTrackingLabel = earth.trackingState.name
            if (earth.trackingState == TrackingState.TRACKING) {
                val pose = earth.cameraGeospatialPose
                checkVpsAvailability(pose.latitude, pose.longitude)
                GeospatialVpsTelemetry(
                    isGeospatialSupported = true,
                    earthState = earthStateLabel,
                    earthTrackingState = earthTrackingLabel,
                    vpsAvailability = _sessionState.value.vps.vpsAvailability,
                    latitude = pose.latitude,
                    longitude = pose.longitude,
                    altitudeMeters = pose.altitude,
                    headingDeg = ((pose.heading % 360.0) + 360.0) % 360.0,
                    horizontalAccuracyM = pose.horizontalAccuracy,
                    verticalAccuracyM = pose.verticalAccuracy,
                    orientationAccuracyDeg = pose.orientationYawAccuracy,
                    resolvedVpsAnchorsCount = resolvedCloudAnchors.values.count {
                        it.anchor.trackingState == TrackingState.TRACKING
                    }
                )
            } else {
                _sessionState.value.vps.copy(
                    isGeospatialSupported = true,
                    earthState = earthStateLabel,
                    earthTrackingState = earthTrackingLabel,
                    resolvedVpsAnchorsCount = resolvedCloudAnchors.values.count {
                        it.anchor.trackingState == TrackingState.TRACKING
                    }
                )
            }
        } else {
            _sessionState.value.vps
        }

        _sessionState.update { current ->
            current.copy(
                trackingState = trackingLabel,
                detectedPlanesCount = planeCount,
                activeAnchorsCount = activeAnchors,
                frameTimestampNs = frame.timestamp,
                frameCount = current.frameCount + 1L,
                lightIntensity = lightPixelIntensity,
                vps = vpsTelemetry,
                statusMessage = when {
                    vpsTelemetry.earthTrackingState == "TRACKING" ->
                        "VPS LOCKED ±${String.format("%.1f", vpsTelemetry.horizontalAccuracyM)}m • $planeCount planes • ${vpsTelemetry.resolvedVpsAnchorsCount} geo-anchors"
                    cameraTracking == TrackingState.TRACKING ->
                        "ARCore tracking • $planeCount planes • Localizing VPS..."
                    else ->
                        "ARCore locating street features..."
                }
            )
        }
    }

    private fun drawCameraBackground(frame: Frame) {
        if (cameraTextureId == -1 || shaderProgram == 0) return

        if (frame.hasDisplayGeometryChanged() || quadTexCoordsTransformed.position() == 0 && !hasInitializedTexCoords) {
            quadCoords.position(0)
            quadTexCoordsTransformed.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoordsTransformed
            )
            hasInitializedTexCoords = true
        }

        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)

        GLES20.glUseProgram(shaderProgram)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(textureUniform, 0)

        quadCoords.position(0)
        GLES20.glVertexAttribPointer(positionAttrib, 2, GLES20.GL_FLOAT, false, 0, quadCoords)
        GLES20.glEnableVertexAttribArray(positionAttrib)

        quadTexCoordsTransformed.position(0)
        GLES20.glVertexAttribPointer(texCoordAttrib, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordsTransformed)
        GLES20.glEnableVertexAttribArray(texCoordAttrib)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(positionAttrib)
        GLES20.glDisableVertexAttribArray(texCoordAttrib)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
    }

    private var hasInitializedTexCoords = false

    fun createAnchorFromHit(
        xPx: Float,
        yPx: Float,
        label: String,
        frame: Frame? = latestFrame
    ): GraffitiAnchorRecord? {
        val targetFrame = frame ?: return null
        val hits: List<HitResult> = try {
            targetFrame.hitTest(xPx, yPx)
        } catch (_: Throwable) {
            return null
        }

        val validHit = hits.firstOrNull { hit ->
            val trackable = hit.trackable
            trackable is Plane &&
                trackable.trackingState == TrackingState.TRACKING &&
                trackable.isPoseInPolygon(hit.hitPose)
        } ?: return null

        return try {
            val anchor = validHit.createAnchor()
            val record = GraffitiAnchorRecord(
                id = "ar_hit_${System.currentTimeMillis()}",
                label = label,
                anchor = anchor
            )
            trackedAnchors.add(record)
            _sessionState.update { it.copy(activeAnchorsCount = trackedAnchors.size) }
            record
        } catch (_: Throwable) {
            null
        }
    }

    fun createForwardAnchor(
        label: String,
        distanceMeters: Float = 2.0f
    ): GraffitiAnchorRecord? {
        val activeSession = session ?: return null
        if (!_sessionState.value.isSessionResumed) return null
        return try {
            val frame = latestFrame ?: activeSession.update()
            if (frame.camera.trackingState != TrackingState.TRACKING) return null

            val cameraPose = frame.camera.displayOrientedPose
            val forwardOffset = Pose.makeTranslation(0f, 0f, -distanceMeters)
            val targetPose = cameraPose.compose(forwardOffset)
            val anchor = activeSession.createAnchor(targetPose)
            val record = GraffitiAnchorRecord(
                id = "ar_fwd_${System.currentTimeMillis()}",
                label = label,
                anchor = anchor
            )
            trackedAnchors.add(record)
            _sessionState.update {
                it.copy(
                    activeAnchorsCount = trackedAnchors.size,
                    statusMessage = "Anchored \"$label\" in ARCore space (${trackedAnchors.size} active)"
                )
            }
            record
        } catch (_: Throwable) {
            null
        }
    }

    fun clearAnchors() {
        trackedAnchors.forEach { record ->
            try {
                record.anchor.detach()
            } catch (_: Throwable) {
            }
        }
        trackedAnchors.clear()
        resolvedCloudAnchors.clear()
        pendingAnchorResolves.clear()
        _sessionState.update { cur ->
            cur.copy(
                activeAnchorsCount = 0,
                vps = cur.vps.copy(resolvedVpsAnchorsCount = 0)
            )
        }
    }

    fun destroySession() {
        availabilityJob?.cancel()
        clearAnchors()
        latestFrame = null
        hasInitializedTexCoords = false
        try {
            session?.close()
        } catch (_: Throwable) {
        }
        session = null
        _sessionState.update {
            it.copy(
                isSessionCreated = false,
                isSessionResumed = false,
                trackingState = "STANDBY",
                detectedPlanesCount = 0,
                activeAnchorsCount = 0,
                frameTimestampNs = 0L
            )
        }
    }

    private fun canProbeOrInstallArCore(): Boolean {
        if (isPackageInstalled("com.google.ar.core")) return true
        if (isEmulator()) return false
        return canResolvePlayStoreInstallService()
    }

    private fun isPackageInstalled(packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun canResolvePlayStoreInstallService(): Boolean {
        return try {
            val intent = Intent("com.google.android.play.core.install.BIND_INSTALL_SERVICE")
                .setPackage("com.android.vending")
            val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.resolveService(intent, PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.resolveService(intent, 0)
            }
            resolved != null
        } catch (_: Throwable) {
            false
        }
    }

    private fun isEmulator(): Boolean {
        return Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("google_sdk") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for x86") ||
            Build.MANUFACTURER.contains("Genymotion") ||
            (Build.BRAND.startsWith("generic") && Build.DEVICE.startsWith("generic")) ||
            "google_sdk" == Build.PRODUCT ||
            Build.HARDWARE.contains("goldfish") ||
            Build.HARDWARE.contains("ranchu")
    }

    private fun resolveDisplayRotation(): Int {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.display?.rotation ?: Surface.ROTATION_0
            } else {
                @Suppress("DEPRECATION")
                val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                @Suppress("DEPRECATION")
                wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0
            }
        } catch (_: Throwable) {
            Surface.ROTATION_0
        }
    }

    private fun updateError(
        availability: ArCoreAvailabilityStatus,
        message: String,
        throwable: Throwable
    ) {
        _sessionState.update {
            it.copy(
                availability = availability,
                isSessionCreated = false,
                isSessionResumed = false,
                trackingState = "FALLBACK",
                statusMessage = message,
                errorMessage = throwable.message
            )
        }
    }

    private fun mapAvailability(availability: ArCoreApk.Availability): ArCoreAvailabilityStatus {
        return when (availability) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArCoreAvailabilityStatus.SUPPORTED_INSTALLED
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD -> ArCoreAvailabilityStatus.SUPPORTED_APK_TOO_OLD
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArCoreAvailabilityStatus.SUPPORTED_NOT_INSTALLED
            ArCoreApk.Availability.UNKNOWN_CHECKING -> ArCoreAvailabilityStatus.CHECKING
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT -> ArCoreAvailabilityStatus.UNSUPPORTED_DEVICE
        }
    }

    private fun buildCameraShaderProgram(): Int {
        val vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER)
        val fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER)
        val program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertexShader)
        GLES20.glAttachShader(program, fragmentShader)
        GLES20.glLinkProgram(program)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        return shader
    }

    companion object {
        private const val TAG = "ArSessionManager"

        private val QUAD_COORDS = floatArrayOf(
            -1.0f, -1.0f,
            -1.0f, +1.0f,
            +1.0f, -1.0f,
            +1.0f, +1.0f
        )

        private const val CAMERA_VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """

        private const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 v_TexCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, v_TexCoord);
            }
        """
    }
}

@Composable
fun rememberArSessionManager(
    enabled: Boolean = true,
    onDisposeDestroy: Boolean = true
): ArSessionManager {
    val context = LocalContext.current
    val manager = remember(context) { ArSessionManager(context) }
    ArSessionLifecycleEffect(
        sessionManager = manager,
        enabled = enabled,
        destroyOnDispose = onDisposeDestroy
    )
    return manager
}

@Composable
fun ArSessionLifecycleEffect(
    sessionManager: ArSessionManager,
    enabled: Boolean,
    destroyOnDispose: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    val currentEnabled by rememberUpdatedState(enabled)

    DisposableEffect(lifecycleOwner, sessionManager, enabled) {
        if (enabled) {
            if (sessionManager.initializeSession(activity)) {
                sessionManager.resumeSession()
            }
        } else {
            sessionManager.pauseSession()
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (currentEnabled && sessionManager.initializeSession(activity)) {
                        sessionManager.resumeSession()
                    }
                }
                Lifecycle.Event.ON_PAUSE -> sessionManager.pauseSession()
                Lifecycle.Event.ON_DESTROY -> if (destroyOnDispose) sessionManager.destroySession()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (destroyOnDispose) {
                sessionManager.destroySession()
            } else {
                sessionManager.pauseSession()
            }
        }
    }
}

@Composable
fun ArCoreCameraSurface(
    sessionManager: ArSessionManager,
    modifier: Modifier = Modifier,
    onFrameUpdate: ((Frame) -> Unit)? = null
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestOnFrameUpdate by rememberUpdatedState(onFrameUpdate)
    val glSurfaceViewRef = remember { mutableListOf<GLSurfaceView>() }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val view = glSurfaceViewRef.firstOrNull() ?: return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_RESUME -> view.onResume()
                Lifecycle.Event.ON_PAUSE -> view.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glSurfaceViewRef.firstOrNull()?.onPause()
        }
    }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                preserveEGLContextOnPause = true
                setEGLContextClientVersion(2)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
                setRenderer(object : GLSurfaceView.Renderer {
                    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
                        sessionManager.onSurfaceCreated()
                    }

                    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
                        sessionManager.onSurfaceChanged(width, height)
                    }

                    override fun onDrawFrame(gl: GL10?) {
                        sessionManager.onDrawFrame { frame ->
                            latestOnFrameUpdate?.invoke(frame)
                        }
                    }
                })
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                glSurfaceViewRef.clear()
                glSurfaceViewRef.add(this)
            }
        },
        modifier = modifier
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
