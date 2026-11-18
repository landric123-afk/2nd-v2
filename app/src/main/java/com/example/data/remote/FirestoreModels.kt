package com.example.data.remote

import android.util.Log
import androidx.annotation.Keep
import com.example.geo.GeohashUtil
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class OperationType(val value: String) {
    CREATE("create"),
    UPDATE("update"),
    DELETE("delete"),
    LIST("list"),
    GET("get"),
    WRITE("write"),
}

fun handleFirestoreError(exception: Throwable, operationType: OperationType, path: String?): String {
    val causes = generateSequence(exception) { it.cause }
    val rootError = causes.firstOrNull { it is FirebaseFirestoreException }
        ?: causes.firstOrNull { it !is CancellationException }
        ?: exception

    val auth = FirebaseAuth.getInstance()
    val currentUser = auth.currentUser

    val providerInfoList = currentUser?.providerData?.map { provider ->
        JSONObject().apply {
            put("providerId", provider.providerId)
            put("email", provider.email)
        }
    } ?: emptyList()

    val authInfoJson = JSONObject().apply {
        put("userId", currentUser?.uid)
        put("email", currentUser?.email)
        put("emailVerified", currentUser?.isEmailVerified)
        put("tenantId", currentUser?.tenantId)
        put("providerInfo", JSONArray(providerInfoList))
    }

    val errorInfoJson = JSONObject().apply {
        put("error", rootError.message ?: rootError.toString())
        put("operationType", operationType.value)
        put("path", path)
        put("authInfo", authInfoJson)
    }

    val jsonString = errorInfoJson.toString()
    Log.e("FirestoreError", "Firestore Error: $jsonString")
    return jsonString
}

@Keep
@IgnoreExtraProperties
data class UserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val handle: String = "",
    val email: String = "",
    val bio: String = "",
    val photoUrl: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> {
        val raw = mutableMapOf<String, Any?>(
            "userId" to userId,
            "displayName" to displayName.take(50).ifBlank { "Operator" },
            "handle" to handle.take(30).ifBlank { "unit_2nd" },
            "email" to email.take(120),
            "bio" to bio.take(200),
            "photoUrl" to photoUrl?.take(500),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class CloudSoundDrop(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val description: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val radiusInMeters: Double = 150.0,
    val geohash: String = "",
    val geohashTile: String = "",
    val anonymous: Boolean = false,
    val audioDurationSeconds: Long = 0L,
    val creatorName: String = "",
    val dspPreset: String = "RAW",
    val audioUrl: String? = null,
    val audioStoragePath: String? = null,
    val clientTimestampMs: Long = 0L,
    val localAudioPath: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> {
        val lat = latitude.coerceIn(-90.0, 90.0)
        val lng = longitude.coerceIn(-180.0, 180.0)
        val hash = geohash.ifBlank { GeohashUtil.encode(lat, lng) }.take(12)
        val tile = geohashTile.ifBlank { hash.take(GeohashUtil.TILE_PRECISION) }.take(5)
        val raw = mutableMapOf<String, Any?>(
            "userId" to userId,
            "title" to title.take(80).ifBlank { "Signal Beacon" },
            "description" to description.take(300),
            "latitude" to lat,
            "longitude" to lng,
            "radiusInMeters" to radiusInMeters.coerceIn(50.0, 1000.0),
            "geohash" to hash,
            "geohashTile" to tile,
            "anonymous" to anonymous,
            "audioDurationSeconds" to audioDurationSeconds.coerceIn(0L, 3600L),
            "creatorName" to creatorName.take(50).ifBlank { "Anonymous" },
            "dspPreset" to dspPreset.take(32).ifBlank { "RAW" },
            "audioUrl" to audioUrl?.take(1024),
            "audioStoragePath" to audioStoragePath?.take(500),
            "localAudioPath" to localAudioPath?.take(500),
            "clientTimestampMs" to clientTimestampMs.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }

    fun toPublicMap(): Map<String, Any> {
        val lat = latitude.coerceIn(-90.0, 90.0)
        val lng = longitude.coerceIn(-180.0, 180.0)
        val hash = geohash.ifBlank { GeohashUtil.encode(lat, lng) }.take(12)
        val tile = geohashTile.ifBlank { hash.take(GeohashUtil.TILE_PRECISION) }.take(5)
        val raw = mutableMapOf<String, Any?>(
            "userId" to userId,
            "title" to title.take(80).ifBlank { "Signal Beacon" },
            "description" to description.take(300),
            "latitude" to lat,
            "longitude" to lng,
            "radiusInMeters" to radiusInMeters.coerceIn(50.0, 1000.0),
            "geohash" to hash,
            "geohashTile" to tile,
            "anonymous" to anonymous,
            "audioDurationSeconds" to audioDurationSeconds.coerceIn(0L, 3600L),
            "creatorName" to if (anonymous) "Anonymous" else creatorName.take(50).ifBlank { "Operator" },
            "dspPreset" to dspPreset.take(32).ifBlank { "RAW" },
            "audioUrl" to audioUrl?.take(1024),
            "audioStoragePath" to audioStoragePath?.take(500),
            "clientTimestampMs" to clientTimestampMs.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }
}

@Keep
@IgnoreExtraProperties
data class CloudVirtualGraffiti(
    @DocumentId val id: String = "",
    val userId: String = "",
    val contentText: String = "",
    val author: String = "",
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitudeMeters: Double = 0.0,
    val geohash: String = "",
    val geohashTile: String = "",
    val anchorType: String = "COMPASS_FALLBACK",
    val anchorAzimuth: Double = 0.0,
    val anchorPitch: Double = 0.0,
    val vpsHeadingDeg: Double = 0.0,
    val horizontalAccuracyM: Double = 0.0,
    val verticalAccuracyM: Double = 0.0,
    val colorHex: String = "#FF0055",
    val sizeScale: Double = 1.0,
    val mediaType: String = "TEXT",
    val mediaUrl: String? = null,
    val mediaStoragePath: String? = null,
    val clientTimestampMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun toCreateMap(): Map<String, Any> {
        val lat = latitude.coerceIn(-90.0, 90.0)
        val lng = longitude.coerceIn(-180.0, 180.0)
        val hash = geohash.ifBlank { GeohashUtil.encode(lat, lng) }.take(12)
        val tile = geohashTile.ifBlank { hash.take(GeohashUtil.TILE_PRECISION) }.take(5)
        val safeAnchorType = if (anchorType in ALLOWED_ANCHOR_TYPES) anchorType else "COMPASS_FALLBACK"
        val safeMediaType = if (mediaType in ALLOWED_MEDIA_TYPES) mediaType else "TEXT"

        val raw = mutableMapOf<String, Any?>(
            "userId" to userId,
            "contentText" to contentText.take(140).ifBlank { "MEMENTO PROXIMA" },
            "author" to author.take(40).ifBlank { "underboard" },
            "latitude" to lat,
            "longitude" to lng,
            "altitudeMeters" to altitudeMeters.coerceIn(-500.0, 10000.0),
            "geohash" to hash,
            "geohashTile" to tile,
            "anchorType" to safeAnchorType,
            "anchorAzimuth" to anchorAzimuth.coerceIn(0.0, 360.0),
            "anchorPitch" to anchorPitch.coerceIn(-90.0, 90.0),
            "vpsHeadingDeg" to vpsHeadingDeg.coerceIn(0.0, 360.0),
            "horizontalAccuracyM" to horizontalAccuracyM.coerceIn(0.0, 5000.0),
            "verticalAccuracyM" to verticalAccuracyM.coerceIn(0.0, 5000.0),
            "colorHex" to colorHex.take(9).ifBlank { "#FF0055" },
            "sizeScale" to sizeScale.coerceIn(0.3, 3.0),
            "mediaType" to safeMediaType,
            "mediaUrl" to mediaUrl?.take(1024),
            "mediaStoragePath" to mediaStoragePath?.take(500),
            "clientTimestampMs" to clientTimestampMs.coerceAtLeast(0L),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        @Suppress("UNCHECKED_CAST")
        return raw.filterValues { it != null } as Map<String, Any>
    }

    private companion object {
        val ALLOWED_ANCHOR_TYPES = setOf("VPS_TERRAIN", "VPS_ROOFTOP", "VPS_WGS84", "COMPASS_FALLBACK")
        val ALLOWED_MEDIA_TYPES = setOf("TEXT", "IMAGE", "PDF")
    }
}
