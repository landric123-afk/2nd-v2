package com.example.ui.viewmodel

import android.app.Activity
import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ar.ArSessionManager
import com.example.ar.ArSessionState
import com.example.ar.VpsAnchorSurfaceMode
import com.example.audio.AudioRecorderManager
import com.example.audio.VoiceFilterPreset
import com.example.data.database.SecondDatabase
import com.example.data.database.SoundDropEntity
import com.example.data.database.VirtualGraffitiEntity
import com.example.data.remote.CloudSoundDrop
import com.example.data.remote.CloudVirtualGraffiti
import com.example.data.remote.FirestoreRepository
import com.example.data.remote.UiState
import com.example.data.remote.UserProfile
import com.example.data.repository.SecondRepository
import com.example.geo.GeohashUtil
import com.example.location.GeofenceRegistrationStatus
import com.example.location.LocationManager
import com.example.service.NotificationHelper
import com.example.storage.CloudMediaStorageManager
import com.example.ui.components.TimelineUploadMarker
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(
    application: Application,
    private val firestoreRepository: FirestoreRepository,
    private val currentUserId: String
) : AndroidViewModel(application), SensorEventListener {

    private val localRepository: SecondRepository =
        SecondRepository(SecondDatabase.getDatabase(application).secondDao())
    private val sensorManager: SensorManager? =
        application.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = LocationManager(application)
    private val audioManager = AudioRecorderManager(application)
    private val storageManager = CloudMediaStorageManager(application)

    val arSessionManager = ArSessionManager(application, viewModelScope)
    val arCoreState: StateFlow<ArSessionState> = arSessionManager.sessionState
    val geofenceStatus: StateFlow<GeofenceRegistrationStatus> = locationManager.geofenceStatus

    // User Position (Defaults around Mountain View baseline until live GPS locks)
    private val _userLatitude = MutableStateFlow(37.4220)
    val userLatitude: StateFlow<Double> = _userLatitude.asStateFlow()

    private val _userLongitude = MutableStateFlow(-122.0840)
    val userLongitude: StateFlow<Double> = _userLongitude.asStateFlow()

    private val _isLiveGpsActive = MutableStateFlow(false)
    val isLiveGpsActive: StateFlow<Boolean> = _isLiveGpsActive.asStateFlow()

    val activeGeohashTile: StateFlow<String> = combine(_userLatitude, _userLongitude) { lat, lng ->
        GeohashUtil.tilePrefix(lat, lng)
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), GeohashUtil.tilePrefix(37.4220, -122.0840))

    private val nearbyGeohashTilesFlow = combine(_userLatitude, _userLongitude) { lat, lng ->
        GeohashUtil.nearbyTiles(lat, lng)
    }.distinctUntilChanged()

    // Timeline Hours scale: 0 = ALL, 1, 2, 6, 12, 24, 48
    private val _timelineFilterHours = MutableStateFlow(0)
    val timelineFilterHours: StateFlow<Int> = _timelineFilterHours.asStateFlow()

    // Live Sensors
    private val _deviceAzimuth = MutableStateFlow(120f)
    val deviceAzimuth: StateFlow<Float> = _deviceAzimuth.asStateFlow()

    private val _devicePitch = MutableStateFlow(5f)
    val devicePitch: StateFlow<Float> = _devicePitch.asStateFlow()

    // Profile state (Firestore-backed)
    val userProfileState: StateFlow<UiState<UserProfile?>> = firestoreRepository.observeUserProfile(currentUserId)
        .map<UserProfile?, UiState<UserProfile?>> { UiState.Success(it) }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val rootError = firestoreError ?: error
            Log.w(TAG, "Error observing profile", rootError)
            emit(UiState.Error(rootError.message ?: "Failed to load profile"))
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), UiState.Loading)

    private val _isSavingProfile = MutableStateFlow(false)
    val isSavingProfile: StateFlow<Boolean> = _isSavingProfile.asStateFlow()

    private val _profileSaveMessage = MutableStateFlow<String?>(null)
    val profileSaveMessage: StateFlow<String?> = _profileSaveMessage.asStateFlow()

    private val _mediaSyncNotice = MutableStateFlow<String?>(null)
    val mediaSyncNotice: StateFlow<String?> = _mediaSyncNotice.asStateFlow()

    // Audio Recording, DSP Filtering & Preview State
    private val _isRecordingAudio = MutableStateFlow(false)
    val isRecordingAudio: StateFlow<Boolean> = _isRecordingAudio.asStateFlow()

    private val _recordedAmplitudePoints = MutableStateFlow<List<Float>>(emptyList())
    val recordedAmplitudePoints: StateFlow<List<Float>> = _recordedAmplitudePoints.asStateFlow()

    private val _recordingDuration = MutableStateFlow(0)
    val recordingDuration: StateFlow<Int> = _recordingDuration.asStateFlow()

    private val _pendingRecordedAudioPath = MutableStateFlow<String?>(null)
    val pendingRecordedAudioPath: StateFlow<String?> = _pendingRecordedAudioPath.asStateFlow()

    private val _selectedVoicePreset = MutableStateFlow(VoiceFilterPreset.RAW)
    val selectedVoicePreset: StateFlow<VoiceFilterPreset> = _selectedVoicePreset.asStateFlow()

    private val _isPreviewPlaying = MutableStateFlow(false)
    val isPreviewPlaying: StateFlow<Boolean> = _isPreviewPlaying.asStateFlow()

    private val _isUsingHardwareMic = MutableStateFlow(false)
    val isUsingHardwareMic: StateFlow<Boolean> = _isUsingHardwareMic.asStateFlow()

    private var recordingJob: Job? = null
    private var previewJob: Job? = null

    // Audio Playback State
    private val _playingDropId = MutableStateFlow<Int?>(null)
    val playingDropId: StateFlow<Int?> = _playingDropId.asStateFlow()

    private val _playbackProgress = MutableStateFlow(0f)
    val playbackProgress: StateFlow<Float> = _playbackProgress.asStateFlow()

    private var playbackJob: Job? = null

    private val cloudSoundDropsFlow = firestoreRepository.observeSoundDrops(currentUserId)
        .map { cloudDrops ->
            cloudDrops.mapIndexed { idx, cloud -> cloud.toEntity(idx, isCommunity = false) }
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Cloud SoundDrops stream fallback to local cache", firestoreError ?: error)
            emit(emptyList())
        }

    private val publicSoundDropsFlow = nearbyGeohashTilesFlow
        .flatMapLatest { tiles ->
            firestoreRepository.observePublicSoundDrops(tiles)
                .map { publicDrops ->
                    publicDrops
                        .filter { it.userId != currentUserId }
                        .mapIndexed { idx, cloud -> cloud.toEntity(idx + 5000, isCommunity = true) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Public geohash drops stream fallback", firestoreError ?: error)
                    emit(emptyList())
                }
        }

    private val cloudGraffitiFlow = firestoreRepository.observeVirtualGraffiti(currentUserId)
        .map { cloudGraffiti ->
            cloudGraffiti.mapIndexed { idx, cloud -> cloud.toEntity(idx, isCommunity = false) }
        }
        .catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            Log.w(TAG, "Cloud Graffiti stream fallback to local cache", firestoreError ?: error)
            emit(emptyList())
        }

    private val publicGraffitiFlow = nearbyGeohashTilesFlow
        .flatMapLatest { tiles ->
            firestoreRepository.observePublicVirtualGraffiti(tiles)
                .map { publicGraffiti ->
                    publicGraffiti
                        .filter { it.userId != currentUserId }
                        .mapIndexed { idx, cloud -> cloud.toEntity(idx + 5000, isCommunity = true) }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Public geohash graffiti stream fallback", firestoreError ?: error)
                    emit(emptyList())
                }
        }

    private val combinedSoundDrops = combine(
        localRepository.allSoundDrops,
        cloudSoundDropsFlow,
        publicSoundDropsFlow
    ) { local, ownCloud, communityCloud ->
        (ownCloud + communityCloud + local)
            .distinctBy { it.publicId ?: it.cloudId ?: "local_${it.id}" }
            .sortedByDescending { it.timestamp }
    }

    private val combinedGraffiti = combine(
        localRepository.allVirtualGraffiti,
        cloudGraffitiFlow,
        publicGraffitiFlow
    ) { local, ownCloud, communityCloud ->
        (ownCloud + communityCloud + local)
            .distinctBy { it.publicId ?: it.cloudId ?: "local_${it.id}" }
            .sortedByDescending { it.timestamp }
    }

    val soundDropsList: StateFlow<List<SoundDropEntity>> = combinedSoundDrops
        .combine(_timelineFilterHours) { list, hours ->
            if (hours == 0) {
                list
            } else {
                val cutoff = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
                list.filter { it.timestamp >= cutoff }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val virtualGraffitiList: StateFlow<List<VirtualGraffitiEntity>> = combinedGraffiti
        .combine(_timelineFilterHours) { list, hours ->
            if (hours == 0) {
                list
            } else {
                val cutoff = System.currentTimeMillis() - (hours * 60 * 60 * 1000L)
                list.filter { it.timestamp >= cutoff }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val communityDiscoveryCount: StateFlow<Int> = combine(soundDropsList, virtualGraffitiList) { drops, graf ->
        drops.count { it.isFromCommunity } + graf.count { it.isFromCommunity }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val timelineUploadMarkers: StateFlow<List<TimelineUploadMarker>> = combine(
        combinedSoundDrops,
        combinedGraffiti
    ) { drops, graffiti ->
        (drops.map { TimelineUploadMarker(it.timestamp, isGraffiti = false) } +
            graffiti.map { TimelineUploadMarker(it.timestamp, isGraffiti = true) })
            .sortedBy { it.timestamp }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        NotificationHelper.createNotificationChannel(application)
        registerSensors()

        viewModelScope.launch {
            localRepository.prepopulateIfEmpty()
            Firebase.auth.currentUser?.let { firebaseUser ->
                if (firebaseUser.uid == currentUserId) {
                    firestoreRepository.ensureUserProfile(firebaseUser)
                }
            }
        }

        // Sync circular geofences whenever drops or graffiti lists update
        viewModelScope.launch {
            combine(combinedSoundDrops, combinedGraffiti) { drops, graffiti ->
                drops to graffiti
            }.collect { (drops, graffiti) ->
                locationManager.syncCircularGeofences(drops, graffiti)
                locationManager.evaluateCircularGeofences(
                    lat = _userLatitude.value,
                    lng = _userLongitude.value,
                    soundDrops = drops,
                    graffitiList = graffiti
                )
            }
        }

        // Sync persistent ARCore Geospatial VPS anchors once Earth tracking locks
        viewModelScope.launch {
            combine(arCoreState, combinedGraffiti) { state, graffiti ->
                state to graffiti
            }.collect { (state, graffiti) ->
                if (state.vps.earthTrackingState == "TRACKING") {
                    arSessionManager.syncGeospatialAnchors(graffiti)
                }
            }
        }
    }

    private fun CloudSoundDrop.toEntity(idx: Int, isCommunity: Boolean): SoundDropEntity {
        val deterministicId = if (isCommunity) {
            -(100000 + idx + (id.hashCode() and 0x0FFF))
        } else {
            -(idx + 1 + (id.hashCode() and 0x0FFF))
        }
        return SoundDropEntity(
            id = deterministicId,
            cloudId = if (isCommunity) null else id,
            publicId = id,
            ownerUserId = userId,
            title = title,
            description = description,
            latitude = latitude,
            longitude = longitude,
            radiusInMeters = radiusInMeters,
            isAnonymous = anonymous,
            timestamp = clientTimestampMs.takeIf { it > 0L }
                ?: createdAt?.toDate()?.time
                ?: System.currentTimeMillis(),
            audioDurationSeconds = audioDurationSeconds.toInt(),
            creatorName = creatorName,
            dspPreset = dspPreset,
            audioUrl = audioUrl,
            localAudioPath = localAudioPath,
            geohash = geohash,
            isFromCommunity = isCommunity
        )
    }

    private fun CloudVirtualGraffiti.toEntity(idx: Int, isCommunity: Boolean): VirtualGraffitiEntity {
        val deterministicId = if (isCommunity) {
            -(100000 + idx + (id.hashCode() and 0x0FFF))
        } else {
            -(idx + 1 + (id.hashCode() and 0x0FFF))
        }
        return VirtualGraffitiEntity(
            id = deterministicId,
            cloudId = if (isCommunity) null else id,
            publicId = id,
            ownerUserId = userId,
            contentText = contentText,
            author = author,
            latitude = latitude,
            longitude = longitude,
            altitudeMeters = altitudeMeters,
            anchorType = anchorType,
            anchorAzimuth = anchorAzimuth.toFloat(),
            anchorPitch = anchorPitch.toFloat(),
            vpsHeadingDeg = vpsHeadingDeg.toFloat(),
            horizontalAccuracyM = horizontalAccuracyM.toFloat(),
            verticalAccuracyM = verticalAccuracyM.toFloat(),
            colorHex = colorHex,
            sizeScale = sizeScale.toFloat(),
            mediaType = mediaType,
            mediaUrl = mediaUrl,
            geohash = geohash,
            timestamp = clientTimestampMs.takeIf { it > 0L }
                ?: createdAt?.toDate()?.time
                ?: System.currentTimeMillis(),
            isFromCommunity = isCommunity
        )
    }

    private fun checkGeofences(lat: Double, lng: Double) {
        viewModelScope.launch {
            val drops = combinedSoundDrops.first()
            val graffiti = combinedGraffiti.first()
            locationManager.evaluateCircularGeofences(lat, lng, drops, graffiti)
        }
    }

    fun moveUser(latOffset: Double, lngOffset: Double) {
        stopLiveLocationUpdates()
        val newLat = _userLatitude.value + latOffset
        val newLng = _userLongitude.value + lngOffset
        _userLatitude.value = newLat
        _userLongitude.value = newLng
        checkGeofences(newLat, newLng)
    }

    fun setUserCoordinates(lat: Double, lng: Double) {
        _userLatitude.value = lat
        _userLongitude.value = lng
        checkGeofences(lat, lng)
    }

    fun setTimelineFilter(hours: Int) {
        _timelineFilterHours.value = hours
    }

    fun clearMediaSyncNotice() {
        _mediaSyncNotice.value = null
    }

    fun saveUserProfile(displayName: String, handle: String, bio: String) {
        if (_isSavingProfile.value) return
        _isSavingProfile.value = true
        _profileSaveMessage.value = null
        viewModelScope.launch {
            val result = firestoreRepository.updateUserProfile(
                userId = currentUserId,
                displayName = displayName,
                handle = handle,
                bio = bio
            )
            _isSavingProfile.value = false
            _profileSaveMessage.value = if (result.isSuccess) {
                "Profile synced to cloud."
            } else {
                result.exceptionOrNull()?.localizedMessage ?: "Failed to save profile"
            }
        }
    }

    fun prepareForSignOut() {
        viewModelScope.launch {
            withContext(NonCancellable) {
                stopLiveLocationUpdates()
                audioManager.release()
                arSessionManager.pauseSession()
            }
        }
    }

    // Audio Playback Controller (Remote HTTPS Cloud Storage URL + Local WAV/M4A + Offline Fallback)
    fun toggleSoundDropPlayback(drop: SoundDropEntity) {
        if (_playingDropId.value == drop.id) {
            stopDropPlayback()
            return
        }

        val dist = getDistanceInMeters(_userLatitude.value, _userLongitude.value, drop.latitude, drop.longitude)
        if (dist > drop.radiusInMeters) return

        stopDropPlayback()
        _playingDropId.value = drop.id
        _playbackProgress.value = 0f

        val playableSource = drop.localAudioPath?.takeIf { File(it).exists() }
            ?: drop.audioUrl?.takeIf { it.isNotBlank() }

        val startedHardware = audioManager.startPlayback(playableSource) {
            _playingDropId.value = null
            _playbackProgress.value = 0f
            playbackJob?.cancel()
        }

        playbackJob = viewModelScope.launch {
            if (startedHardware) {
                while (_playingDropId.value == drop.id) {
                    delay(100L)
                    _playbackProgress.value = audioManager.getPlaybackFraction()
                }
            } else {
                val totalSeconds = if (drop.audioDurationSeconds > 0) drop.audioDurationSeconds else 15
                for (tick in 1..(totalSeconds * 10)) {
                    delay(100L)
                    _playbackProgress.value = tick.toFloat() / (totalSeconds * 10).toFloat()
                }
                _playingDropId.value = null
                _playbackProgress.value = 0f
            }
        }
    }

    private fun stopDropPlayback() {
        playbackJob?.cancel()
        audioManager.stopPlayback()
        _playingDropId.value = null
        _playbackProgress.value = 0f
    }

    // Real PCM Microphone Recording + Voice Modulation & Lo-Fi DSP Presets
    fun selectVoiceFilterPreset(preset: VoiceFilterPreset) {
        _selectedVoicePreset.value = preset
        if (_pendingRecordedAudioPath.value != null && !_isRecordingAudio.value) {
            stopPreviewPlayback()
            val updatedPath = audioManager.renderWithPreset(preset)
            if (updatedPath != null) {
                _pendingRecordedAudioPath.value = updatedPath
            }
        }
    }

    fun startRecording() {
        if (_isRecordingAudio.value) return
        stopPreviewPlayback()
        audioManager.discardRecording(_pendingRecordedAudioPath.value)
        _pendingRecordedAudioPath.value = null

        val hardwareStarted = audioManager.startRecording()
        _isUsingHardwareMic.value = hardwareStarted
        _isRecordingAudio.value = true
        _recordingDuration.value = 0
        _recordedAmplitudePoints.value = emptyList()

        recordingJob = viewModelScope.launch {
            var tickCount = 0
            while (_isRecordingAudio.value) {
                delay(100L)
                tickCount++
                val points = _recordedAmplitudePoints.value.toMutableList()
                if (points.size >= 32) points.removeAt(0)

                val amp = if (hardwareStarted) {
                    audioManager.pollNormalizedAmplitude()
                } else {
                    abs(sin(tickCount * 0.45f) * 0.4f + (kotlin.random.Random.nextFloat() * 0.5f + 0.1f) * 0.6f)
                }
                points.add(amp)
                _recordedAmplitudePoints.value = points

                if (tickCount % 10 == 0) {
                    _recordingDuration.value = tickCount / 10
                }
            }
        }
    }

    fun stopRecordingCapture() {
        if (!_isRecordingAudio.value) return
        _isRecordingAudio.value = false
        recordingJob?.cancel()
        val filePath = audioManager.stopAndFinalizeRecording(_selectedVoicePreset.value)
        _pendingRecordedAudioPath.value = filePath
        if (filePath != null) {
            val fileDuration = audioManager.getFileDurationSeconds(filePath)
            if (fileDuration > 0) _recordingDuration.value = fileDuration
        }
    }

    fun togglePreviewRecording() {
        val path = _pendingRecordedAudioPath.value ?: return
        if (_isPreviewPlaying.value) {
            stopPreviewPlayback()
            return
        }
        val started = audioManager.startPlayback(path) {
            _isPreviewPlaying.value = false
            previewJob?.cancel()
        }
        _isPreviewPlaying.value = started
    }

    private fun stopPreviewPlayback() {
        previewJob?.cancel()
        if (_isPreviewPlaying.value) {
            audioManager.stopPlayback()
            _isPreviewPlaying.value = false
        }
    }

    fun stopAndSaveRecording(
        title: String,
        description: String,
        radiusInMeters: Double,
        isAnonymous: Boolean,
        publishToCommunity: Boolean = true,
        targetLat: Double = _userLatitude.value,
        targetLng: Double = _userLongitude.value
    ) {
        if (_isRecordingAudio.value) {
            stopRecordingCapture()
        }
        stopPreviewPlayback()

        val savedPath = _pendingRecordedAudioPath.value
        val activePreset = _selectedVoicePreset.value
        val duration = (_recordingDuration.value).coerceAtLeast(1)
        val profile = (userProfileState.value as? UiState.Success)?.data
        val operatorHandle = profile?.handle?.ifBlank { null } ?: "unit_2nd"
        val creatorLabel = if (isAnonymous) "Anonymous" else operatorHandle
        val resolvedTitle = title.ifBlank { "Beacon #${(100..999).random()}" }
        val nowMs = System.currentTimeMillis()
        val hash = GeohashUtil.encode(targetLat, targetLng)
        val tile = hash.take(GeohashUtil.TILE_PRECISION)

        _pendingRecordedAudioPath.value = null
        _recordedAmplitudePoints.value = emptyList()
        _recordingDuration.value = 0

        viewModelScope.launch {
            var uploadedUrl: String? = null
            var uploadedPath: String? = null
            if (!savedPath.isNullOrBlank()) {
                val uploadRes = storageManager.uploadAudioClip(
                    userId = currentUserId,
                    localFile = File(savedPath),
                    dropKey = "drop_${nowMs}"
                )
                if (uploadRes.isSuccess) {
                    uploadedUrl = uploadRes.getOrNull()?.downloadUrl
                    uploadedPath = uploadRes.getOrNull()?.storagePath
                } else {
                    _mediaSyncNotice.value =
                        "Saved locally & synced metadata. Enable Firebase Storage bucket in Console for cross-user audio streaming."
                }
            }

            val cloudDrop = CloudSoundDrop(
                userId = currentUserId,
                title = resolvedTitle,
                description = description,
                latitude = targetLat,
                longitude = targetLng,
                radiusInMeters = radiusInMeters.coerceIn(50.0, 1000.0),
                geohash = hash,
                geohashTile = tile,
                anonymous = isAnonymous,
                audioDurationSeconds = duration.toLong(),
                creatorName = creatorLabel,
                dspPreset = activePreset.id,
                audioUrl = uploadedUrl,
                audioStoragePath = uploadedPath,
                clientTimestampMs = nowMs,
                localAudioPath = savedPath
            )
            val result = firestoreRepository.createSoundDrop(
                userId = currentUserId,
                drop = cloudDrop,
                publishToPublicIndex = publishToCommunity
            )
            if (result.isFailure) {
                localRepository.insertSoundDrop(
                    SoundDropEntity(
                        title = resolvedTitle,
                        description = description,
                        latitude = targetLat,
                        longitude = targetLng,
                        radiusInMeters = radiusInMeters,
                        isAnonymous = isAnonymous,
                        timestamp = nowMs,
                        audioDurationSeconds = duration,
                        creatorName = creatorLabel,
                        dspPreset = activePreset.id,
                        audioUrl = uploadedUrl,
                        localAudioPath = savedPath,
                        geohash = hash
                    )
                )
            }
            checkGeofences(_userLatitude.value, _userLongitude.value)
        }
    }

    fun deleteSoundDrop(drop: SoundDropEntity) {
        if (drop.isFromCommunity) return
        if (_playingDropId.value == drop.id) stopDropPlayback()
        viewModelScope.launch {
            val cloudId = drop.cloudId
            if (!cloudId.isNullOrBlank()) {
                firestoreRepository.deleteSoundDrop(currentUserId, cloudId)
            } else if (drop.id > 0) {
                localRepository.deleteSoundDrop(drop)
            }
        }
    }

    fun discardRecording() {
        _isRecordingAudio.value = false
        recordingJob?.cancel()
        stopPreviewPlayback()
        audioManager.discardRecording(_pendingRecordedAudioPath.value)
        _pendingRecordedAudioPath.value = null
        _recordedAmplitudePoints.value = emptyList()
        _recordingDuration.value = 0
    }

    fun syncRealGpsLocation() {
        if (!locationManager.hasLocationPermission()) {
            stopLiveLocationUpdates()
            return
        }
        startLiveLocationUpdates(locationManager.hasFineLocationPermission())
        locationManager.fetchLastKnownLocation { lat, lng ->
            setUserCoordinates(lat, lng)
        }
    }

    fun startLiveLocationUpdates(hasFineLocation: Boolean) {
        val started = locationManager.startLocationUpdates(highAccuracy = hasFineLocation) { lat, lng ->
            if (_isLiveGpsActive.value) {
                setUserCoordinates(lat, lng)
            }
        }
        _isLiveGpsActive.value = started
    }

    fun stopLiveLocationUpdates() {
        locationManager.stopLocationUpdates()
        _isLiveGpsActive.value = false
    }

    // ARCore Session & Geospatial VPS Management
    fun checkArCoreAvailability() {
        arSessionManager.checkAvailability()
    }

    fun initializeArCoreSession(activity: Activity): Boolean = arSessionManager.initializeSession(activity)
    fun resumeArCoreSession(): Boolean = arSessionManager.resumeSession()
    fun pauseArCoreSession() = arSessionManager.pauseSession()
    fun destroyArCoreSession() = arSessionManager.destroySession()

    fun dropVirtualGraffiti(
        text: String,
        author: String,
        hexColor: String,
        scale: Float,
        anchorMode: VpsAnchorSurfaceMode = VpsAnchorSurfaceMode.VPS_ROOFTOP,
        mediaType: String = "TEXT",
        selectedMediaUri: Uri? = null,
        publishToCommunity: Boolean = true
    ) {
        val label = text.ifBlank {
            when (mediaType) {
                "IMAGE" -> "AR DECAL"
                "PDF" -> "AR ZINE PDF"
                else -> "MEMENTO PROXIMA"
            }
        }
        val profile = (userProfileState.value as? UiState.Success)?.data
        val resolvedAuthor = author.ifBlank { profile?.handle ?: "underboard" }
        val nowMs = System.currentTimeMillis()
        val vps = arSessionManager.sessionState.value.vps
        val hasVpsPose = vps.earthTrackingState == "TRACKING" && vps.latitude != 0.0
        val effectiveLat = if (hasVpsPose) vps.latitude else _userLatitude.value
        val effectiveLng = if (hasVpsPose) vps.longitude else _userLongitude.value
        val effectiveAlt = if (hasVpsPose) vps.altitudeMeters else 0.0
        val effectiveHeading = if (hasVpsPose) vps.headingDeg else _deviceAzimuth.value.toDouble()
        val hash = GeohashUtil.encode(effectiveLat, effectiveLng)
        val tile = hash.take(GeohashUtil.TILE_PRECISION)
        val anchorKey = "ar_$nowMs"

        if (hasVpsPose) {
            arSessionManager.createGeospatialAnchor(
                key = anchorKey,
                label = label,
                latitude = effectiveLat,
                longitude = effectiveLng,
                altitudeMeters = effectiveAlt,
                mode = anchorMode
            )
        } else {
            arSessionManager.createForwardAnchor(label = label)
        }

        viewModelScope.launch {
            var mediaUrl: String? = null
            var mediaStoragePath: String? = null
            if (selectedMediaUri != null && (mediaType == "IMAGE" || mediaType == "PDF")) {
                val uploadRes = storageManager.uploadGraffitiMedia(
                    userId = currentUserId,
                    contentUri = selectedMediaUri,
                    mediaType = mediaType,
                    graffitiKey = anchorKey
                )
                if (uploadRes.isSuccess) {
                    mediaUrl = uploadRes.getOrNull()?.downloadUrl
                    mediaStoragePath = uploadRes.getOrNull()?.storagePath
                } else {
                    _mediaSyncNotice.value =
                        "AR metadata synced. Enable Firebase Storage in Console to host images/PDFs for other users."
                }
            }

            val cloudGraffiti = CloudVirtualGraffiti(
                userId = currentUserId,
                contentText = label,
                author = resolvedAuthor,
                latitude = effectiveLat,
                longitude = effectiveLng,
                altitudeMeters = effectiveAlt,
                geohash = hash,
                geohashTile = tile,
                anchorType = anchorMode.id,
                anchorAzimuth = _deviceAzimuth.value.toDouble().coerceIn(0.0, 360.0),
                anchorPitch = _devicePitch.value.toDouble().coerceIn(-90.0, 90.0),
                vpsHeadingDeg = effectiveHeading.coerceIn(0.0, 360.0),
                horizontalAccuracyM = vps.horizontalAccuracyM.coerceIn(0.0, 5000.0),
                verticalAccuracyM = vps.verticalAccuracyM.coerceIn(0.0, 5000.0),
                colorHex = hexColor,
                sizeScale = scale.toDouble(),
                mediaType = mediaType,
                mediaUrl = mediaUrl,
                mediaStoragePath = mediaStoragePath,
                clientTimestampMs = nowMs
            )

            val result = firestoreRepository.createVirtualGraffiti(
                userId = currentUserId,
                graffiti = cloudGraffiti,
                publishToPublicIndex = publishToCommunity
            )
            if (result.isFailure) {
                localRepository.insertVirtualGraffiti(
                    VirtualGraffitiEntity(
                        contentText = label,
                        author = resolvedAuthor,
                        latitude = effectiveLat,
                        longitude = effectiveLng,
                        altitudeMeters = effectiveAlt,
                        anchorType = anchorMode.id,
                        anchorAzimuth = _deviceAzimuth.value,
                        anchorPitch = _devicePitch.value,
                        vpsHeadingDeg = effectiveHeading.toFloat(),
                        horizontalAccuracyM = vps.horizontalAccuracyM.toFloat(),
                        verticalAccuracyM = vps.verticalAccuracyM.toFloat(),
                        colorHex = hexColor,
                        sizeScale = scale,
                        mediaType = mediaType,
                        mediaUrl = mediaUrl,
                        localMediaUri = selectedMediaUri?.toString(),
                        geohash = hash,
                        timestamp = nowMs
                    )
                )
            }
            checkGeofences(_userLatitude.value, _userLongitude.value)
        }
    }

    suspend fun preparePdfForViewing(uriOrUrl: String, cacheKey: String): File? {
        return storageManager.copyPdfToCache(uriOrUrl, cacheKey)
    }

    fun wipeDatabase() {
        viewModelScope.launch {
            localRepository.clearAll()
            locationManager.resetBreachHistory()
            localRepository.prepopulateIfEmpty()
        }
    }

    fun manualTurnCompass(degrees: Float) {
        var nextDeg = _deviceAzimuth.value + degrees
        if (nextDeg >= 360f) nextDeg -= 360f
        if (nextDeg < 0f) nextDeg += 360f
        _deviceAzimuth.value = nextDeg
    }

    fun manualTiltPitch(degrees: Float) {
        _devicePitch.value = (_devicePitch.value + degrees).coerceIn(-45f, 45f)
    }

    @Suppress("DEPRECATION")
    private fun registerSensors() {
        val rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val magSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ORIENTATION)
        if (rotationSensor != null) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (magSensor != null) {
            sensorManager.registerListener(this, magSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    @Suppress("DEPRECATION")
    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val rotationMatrix = FloatArray(9)
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            val orientationValues = FloatArray(3)
            SensorManager.getOrientation(rotationMatrix, orientationValues)

            var azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
            if (azimuth < 0) azimuth += 360f
            _deviceAzimuth.value = azimuth
            _devicePitch.value = Math.toDegrees(orientationValues[1].toDouble()).toFloat()
        } else if (event.sensor.type == Sensor.TYPE_ORIENTATION) {
            _deviceAzimuth.value = event.values[0]
            _devicePitch.value = event.values[1]
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onCleared() {
        super.onCleared()
        locationManager.release()
        audioManager.release()
        sensorManager?.unregisterListener(this)
        arSessionManager.destroySession()
        recordingJob?.cancel()
        previewJob?.cancel()
        playbackJob?.cancel()
    }

    fun getDistanceInMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double =
        LocationManager.distanceMeters(lat1, lon1, lat2, lon2)

    private companion object {
        const val TAG = "MainViewModel"
    }
}
