package com.example.data.repository

import com.example.data.database.SecondDao
import com.example.data.database.SoundDropEntity
import com.example.data.database.VirtualGraffitiEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

class SecondRepository(private val secondDao: SecondDao) {
    val allSoundDrops: Flow<List<SoundDropEntity>> = secondDao.getAllSoundDrops()
    val allVirtualGraffiti: Flow<List<VirtualGraffitiEntity>> = secondDao.getAllVirtualGraffiti()

    suspend fun insertSoundDrop(soundDrop: SoundDropEntity) {
        secondDao.insertSoundDrop(soundDrop)
    }

    suspend fun deleteSoundDrop(soundDrop: SoundDropEntity) {
        secondDao.deleteSoundDrop(soundDrop)
    }

    suspend fun insertVirtualGraffiti(graffiti: VirtualGraffitiEntity) {
        secondDao.insertVirtualGraffiti(graffiti)
    }

    suspend fun deleteVirtualGraffiti(graffiti: VirtualGraffitiEntity) {
        secondDao.deleteVirtualGraffiti(graffiti)
    }

    suspend fun clearAll() {
        secondDao.clearAllSoundDrops()
        secondDao.clearAllVirtualGraffiti()
    }

    suspend fun prepopulateIfEmpty() {
        val currentDrops = allSoundDrops.first()
        val currentGraffiti = allVirtualGraffiti.first()
        val now = System.currentTimeMillis()

        if (currentDrops.isEmpty()) {
            // Seed sound drops at offsets around a base coordinate (e.g. London / Paris / NY or typical emulator point 37.422, -122.084)
            val baseLat = 37.4220
            val baseLng = -122.0840

            val sampleDrops = listOf(
                SoundDropEntity(
                    title = "Indie Synths in the Fog",
                    description = "An experimental high-gain synthesizer loop recorded near the concrete flyover.",
                    latitude = baseLat + 0.0012,
                    longitude = baseLng - 0.0008,
                    radiusInMeters = 150.0,
                    isAnonymous = false,
                    timestamp = now - TimeUnit.HOURS.toMillis(2),
                    audioDurationSeconds = 48,
                    creatorName = "neon_phantom"
                ),
                SoundDropEntity(
                    title = "Underground Beats #42",
                    description = "Heavy 808 subbass captured directly from the generator room behind the warehouse.",
                    latitude = baseLat - 0.0018,
                    longitude = baseLng + 0.0011,
                    radiusInMeters = 300.0,
                    isAnonymous = true,
                    timestamp = now - TimeUnit.DAYS.toMillis(1),
                    audioDurationSeconds = 62,
                    creatorName = "Anonymous"
                ),
                SoundDropEntity(
                    title = "The Low-Frequency Whispers",
                    description = "ASMR field recording of thermal vents and metal hums near the subways.",
                    latitude = baseLat + 0.0004,
                    longitude = baseLng + 0.0009,
                    radiusInMeters = 80.0,
                    isAnonymous = false,
                    timestamp = now - TimeUnit.MINUTES.toMillis(30),
                    audioDurationSeconds = 15,
                    creatorName = "frequency_watcher"
                ),
                SoundDropEntity(
                    title = "Rebel Static Broadcast",
                    description = "[INTERCEPTED SIGNAL] Spoken poetry transmission broadcast from the cooling tower rooftop.",
                    latitude = baseLat - 0.0007,
                    longitude = baseLng - 0.0015,
                    radiusInMeters = 220.0,
                    isAnonymous = false,
                    timestamp = now - TimeUnit.HOURS.toMillis(6),
                    audioDurationSeconds = 85,
                    creatorName = "radio_guerrilla"
                )
            )

            for (drop in sampleDrops) {
                secondDao.insertSoundDrop(drop)
            }
        }

        if (currentGraffiti.isEmpty()) {
            val baseLat = 37.4220
            val baseLng = -122.0840

            val sampleGraffiti = listOf(
                VirtualGraffitiEntity(
                    contentText = "THE EMPIRE OF SENSES IS FICTIONAL",
                    author = "stencil_ghost",
                    latitude = baseLat + 0.0005,
                    longitude = baseLng - 0.0006,
                    anchorAzimuth = 45f, // Looking Northeast
                    anchorPitch = 12f,
                    colorHex = "#FF0055", // Hot neon pink
                    sizeScale = 1.3f,
                    timestamp = now - TimeUnit.HOURS.toMillis(4)
                ),
                VirtualGraffitiEntity(
                    contentText = "CRACKS IN THE MASONRY ARE PORTALS",
                    author = "sub_radar",
                    latitude = baseLat - 0.0009,
                    longitude = baseLng + 0.0006,
                    anchorAzimuth = 195f, // Looking South-SouthWest
                    anchorPitch = -5f,
                    colorHex = "#00FF66", // Glowing acid green
                    sizeScale = 0.9f,
                    timestamp = now - TimeUnit.HOURS.toMillis(12)
                ),
                VirtualGraffitiEntity(
                    contentText = "COMPUTED APPARITIONS RESIDE HERE",
                    author = "glitch_tactics",
                    latitude = baseLat + 0.0015,
                    longitude = baseLng + 0.0018,
                    anchorAzimuth = 310f, // Looking Northwest
                    anchorPitch = 25f,
                    colorHex = "#00E5FF", // Cyan glow
                    sizeScale = 1.8f,
                    timestamp = now - TimeUnit.DAYS.toMillis(2)
                )
            )

            for (g in sampleGraffiti) {
                secondDao.insertVirtualGraffiti(g)
            }
        }
    }
}
