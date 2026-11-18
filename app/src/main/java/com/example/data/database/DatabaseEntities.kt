package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sound_drops")
data class SoundDropEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val cloudId: String? = null,
    val publicId: String? = null,
    val ownerUserId: String? = null,
    val title: String,
    val description: String,
    val latitude: Double,
    val longitude: Double,
    val radiusInMeters: Double,
    val isAnonymous: Boolean,
    val timestamp: Long,
    val audioDurationSeconds: Int = 0,
    val creatorName: String = "GuerrillaDrop",
    val dspPreset: String = "RAW",
    val audioUrl: String? = null,
    val localAudioPath: String? = null,
    val geohash: String? = null,
    val isFromCommunity: Boolean = false
)

@Entity(tableName = "virtual_graffiti")
data class VirtualGraffitiEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val cloudId: String? = null,
    val publicId: String? = null,
    val ownerUserId: String? = null,
    val contentText: String,
    val author: String = "AnonStencil",
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0,
    val anchorType: String = "COMPASS_FALLBACK",
    val anchorAzimuth: Float = 0f,
    val anchorPitch: Float = 0f,
    val vpsHeadingDeg: Float = 0f,
    val horizontalAccuracyM: Float = 0f,
    val verticalAccuracyM: Float = 0f,
    val colorHex: String = "#FF0055",
    val sizeScale: Float = 1.0f,
    val mediaType: String = "TEXT",
    val mediaUrl: String? = null,
    val localMediaUri: String? = null,
    val geohash: String? = null,
    val timestamp: Long,
    val isFromCommunity: Boolean = false
)
