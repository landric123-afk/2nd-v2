package com.example

import com.example.audio.AudioDspProcessor
import com.example.audio.VoiceFilterPreset
import com.example.geo.GeohashUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeatureLogicTest {

    @Test
    fun geohash_encodesConsistentTilesAndNearbyGrid() {
        val lat = 37.4220
        val lng = -122.0840

        val fullHash = GeohashUtil.encode(lat, lng)
        val tile = GeohashUtil.tilePrefix(lat, lng)

        assertEquals(GeohashUtil.FULL_PRECISION, fullHash.length)
        assertEquals(GeohashUtil.TILE_PRECISION, tile.length)
        assertTrue(fullHash.startsWith(tile))

        val neighbors = GeohashUtil.nearbyTiles(lat, lng)
        assertEquals(tile, neighbors.first())
        assertEquals(9, neighbors.size)
        assertEquals(9, neighbors.toSet().size)
        assertTrue(neighbors.all { it.length == 5 && it.matches(Regex("^[0-9b-hjkmnp-z]{5}$")) })
    }

    @Test
    fun geohash_haversineAndBoundsAreAccurate() {
        val bounds = GeohashUtil.decodeBounds("9q9hv")
        assertTrue(bounds.minLat < bounds.maxLat)
        assertTrue(bounds.minLng < bounds.maxLng)
        assertTrue(bounds.centerLat in bounds.minLat..bounds.maxLat)
        assertTrue(bounds.centerLng in bounds.minLng..bounds.maxLng)

        val zeroDist = GeohashUtil.haversineMeters(37.422, -122.084, 37.422, -122.084)
        assertEquals(0.0, zeroDist, 0.01)

        val shortWalk = GeohashUtil.haversineMeters(37.4220, -122.0840, 37.4229, -122.0840)
        assertTrue("Expected ~100m walk, got $shortWalk", shortWalk in 90.0..115.0)
    }

    @Test
    fun audioDspPresets_produceValidModulatedSamplesAndWavHeader() {
        val dry = AudioDspProcessor.generateFallbackBeaconPcm(durationSeconds = 1)
        assertEquals(AudioDspProcessor.SAMPLE_RATE, dry.size)

        val deepAnon = AudioDspProcessor.applyPreset(dry, preset = VoiceFilterPreset.DEEP_ANON)
        val tape = AudioDspProcessor.applyPreset(dry, preset = VoiceFilterPreset.TAPE_HISS)
        val radio = AudioDspProcessor.applyPreset(dry, preset = VoiceFilterPreset.RADIO_BANDPASS)

        assertEquals(dry.size, deepAnon.size)
        assertEquals(dry.size, tape.size)
        assertEquals(dry.size, radio.size)

        assertFalse("DEEP_ANON should alter dry signal", dry.contentEquals(deepAnon))
        assertFalse("TAPE_HISS should alter dry signal", dry.contentEquals(tape))
        assertFalse("RADIO_BANDPASS should alter dry signal", dry.contentEquals(radio))
        assertTrue(deepAnon.any { it.toInt() != 0 })

        val outFile = File.createTempFile("dsp_test_", ".wav")
        try {
            AudioDspProcessor.writeWavFile(radio, AudioDspProcessor.SAMPLE_RATE, outFile)
            val bytes = outFile.readBytes()
            assertEquals(44 + radio.size * 2, bytes.size)
            assertEquals("RIFF", String(bytes.sliceArray(0..3)))
            assertEquals("WAVE", String(bytes.sliceArray(8..11)))
            assertEquals("data", String(bytes.sliceArray(36..39)))
        } finally {
            outFile.delete()
        }
    }
}
