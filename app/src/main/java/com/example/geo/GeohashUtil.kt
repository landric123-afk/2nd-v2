package com.example.geo

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure-Kotlin Base32 Geohash encoder and spatial tile grid helper.
 *
 * Uses precision 5 (~4.89km x 4.89km cells) for Firestore `whereIn("geohashTile", ...)` queries
 * over the user's 3x3 surrounding tile grid, while storing precision 9 (~4.7m) full geohashes
 * on every drop and AR anchor document.
 */
object GeohashUtil {

    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private val CHAR_TO_INT = IntArray(128) { -1 }.apply {
        BASE32.forEachIndexed { index, c -> this[c.code] = index }
    }

    const val TILE_PRECISION = 5
    const val FULL_PRECISION = 9

    data class Bounds(
        val minLat: Double,
        val maxLat: Double,
        val minLng: Double,
        val maxLng: Double
    ) {
        val centerLat: Double get() = (minLat + maxLat) / 2.0
        val centerLng: Double get() = (minLng + maxLng) / 2.0
        val latSpan: Double get() = maxLat - minLat
        val lngSpan: Double get() = maxLng - minLng
    }

    fun encode(latitude: Double, longitude: Double, precision: Int = FULL_PRECISION): String {
        val lat = latitude.coerceIn(-90.0, 90.0)
        val lng = longitude.coerceIn(-180.0, 180.0)
        val len = precision.coerceIn(1, 12)

        var minLat = -90.0
        var maxLat = 90.0
        var minLng = -180.0
        var maxLng = 180.0

        var isEvenBit = true
        var bit = 0
        var ch = 0
        val result = StringBuilder(len)

        while (result.length < len) {
            if (isEvenBit) {
                val mid = (minLng + maxLng) / 2.0
                if (lng >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    minLng = mid
                } else {
                    maxLng = mid
                }
            } else {
                val mid = (minLat + maxLat) / 2.0
                if (lat >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    minLat = mid
                } else {
                    maxLat = mid
                }
            }
            isEvenBit = !isEvenBit
            if (bit < 4) {
                bit++
            } else {
                result.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return result.toString()
    }

    fun tilePrefix(latitude: Double, longitude: Double): String {
        return encode(latitude, longitude, TILE_PRECISION)
    }

    fun decodeBounds(geohash: String): Bounds {
        var minLat = -90.0
        var maxLat = 90.0
        var minLng = -180.0
        var maxLng = 180.0
        var isEvenBit = true

        for (c in geohash.lowercase()) {
            val code = c.code
            val cd = if (code in CHAR_TO_INT.indices) CHAR_TO_INT[code] else -1
            if (cd < 0) break
            for (mask in intArrayOf(16, 8, 4, 2, 1)) {
                if (isEvenBit) {
                    val mid = (minLng + maxLng) / 2.0
                    if ((cd and mask) != 0) minLng = mid else maxLng = mid
                } else {
                    val mid = (minLat + maxLat) / 2.0
                    if ((cd and mask) != 0) minLat = mid else maxLat = mid
                }
                isEvenBit = !isEvenBit
            }
        }
        return Bounds(minLat, maxLat, minLng, maxLng)
    }

    /**
     * Returns up to 9 distinct precision-5 geohash tiles covering the center tile
     * and its 8 immediate N/S/E/W/diagonal neighbors (~15km x 15km discovery window).
     */
    fun nearbyTiles(latitude: Double, longitude: Double): List<String> {
        val centerTile = tilePrefix(latitude, longitude)
        val bounds = decodeBounds(centerTile)
        val dLat = bounds.latSpan
        val dLng = bounds.lngSpan

        val tiles = LinkedHashSet<String>(9)
        tiles.add(centerTile)
        for (latStep in -1..1) {
            for (lngStep in -1..1) {
                if (latStep == 0 && lngStep == 0) continue
                val nLat = (bounds.centerLat + latStep * dLat).coerceIn(-89.999, 89.999)
                var nLng = bounds.centerLng + lngStep * dLng
                if (nLng > 180.0) nLng -= 360.0
                if (nLng < -180.0) nLng += 360.0
                tiles.add(encode(nLat, nLng, TILE_PRECISION))
            }
        }
        return tiles.toList()
    }

    fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }
}
