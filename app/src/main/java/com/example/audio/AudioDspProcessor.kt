package com.example.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

enum class VoiceFilterPreset(
    val id: String,
    val label: String,
    val description: String
) {
    RAW("RAW", "CLEAN MIC", "Unaltered dry voice"),
    DEEP_ANON("DEEP_ANON", "SHADOW -4ST", "Pitch-shifted anonymous disguise"),
    TAPE_HISS("TAPE_HISS", "LO-FI TAPE", "Cassette warmth + flutter + hiss"),
    RADIO_BANDPASS("RADIO_BANDPASS", "RADIO TX", "300Hz-2.6kHz street transceiver");

    companion object {
        fun fromId(raw: String?): VoiceFilterPreset =
            entries.firstOrNull { it.id == raw } ?: RAW
    }
}

/**
 * Pure-Kotlin 16-bit PCM DSP processor for anonymous voice modulation and lo-fi presets.
 */
object AudioDspProcessor {

    const val SAMPLE_RATE = 22050

    fun applyPreset(
        dryPcm: ShortArray,
        sampleRate: Int = SAMPLE_RATE,
        preset: VoiceFilterPreset
    ): ShortArray {
        if (dryPcm.isEmpty()) return dryPcm
        return when (preset) {
            VoiceFilterPreset.RAW -> normalize(dryPcm, 0.92f)
            VoiceFilterPreset.DEEP_ANON -> applyDeepAnon(dryPcm, sampleRate)
            VoiceFilterPreset.TAPE_HISS -> applyTapeHiss(dryPcm, sampleRate)
            VoiceFilterPreset.RADIO_BANDPASS -> applyRadioBandpass(dryPcm, sampleRate)
        }
    }

    /**
     * Generates a synthetic test beacon tone when physical microphone hardware is unavailable
     * (e.g., headless emulators) so DSP filter presets remain testable offline.
     */
    fun generateFallbackBeaconPcm(durationSeconds: Int = 3, sampleRate: Int = SAMPLE_RATE): ShortArray {
        val totalSamples = (durationSeconds.coerceIn(1, 15)) * sampleRate
        val out = ShortArray(totalSamples)
        for (i in 0 until totalSamples) {
            val t = i.toDouble() / sampleRate
            val carrier = sin(2.0 * PI * 210.0 * t) * 0.45 +
                sin(2.0 * PI * 420.0 * t) * 0.25 +
                sin(2.0 * PI * 630.0 * t) * 0.15
            val pulse = 0.55 + 0.45 * sin(2.0 * PI * 2.5 * t)
            out[i] = (carrier * pulse * 18000.0).roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return out
    }

    private fun normalize(input: ShortArray, targetPeak: Float): ShortArray {
        var maxSample = 1
        for (s in input) {
            val a = abs(s.toInt())
            if (a > maxSample) maxSample = a
        }
        val gain = ((32767f * targetPeak) / maxSample.toFloat()).coerceAtMost(2.5f)
        return ShortArray(input.size) { idx ->
            (input[idx] * gain).roundToInt().coerceIn(-32767, 32767).toShort()
        }
    }

    /**
     * Lowers fundamental pitch (~0.74x ratio) with overlap-smoothed resampling and a
     * warm low-pass filter to anonymize speaker timbre without robotic clicks.
     */
    private fun applyDeepAnon(input: ShortArray, sampleRate: Int): ShortArray {
        val pitchFactor = 0.74
        val resampled = ShortArray(input.size)
        val grainSize = (sampleRate * 0.045).toInt().coerceAtLeast(256)

        for (i in input.indices) {
            val grainIndex = i / grainSize
            val phaseInGrain = i % grainSize
            val grainStart = (grainIndex * grainSize) % input.size
            val srcPos = grainStart + phaseInGrain * pitchFactor
            val idx0 = srcPos.toInt().coerceIn(0, input.lastIndex)
            val idx1 = (idx0 + 1).coerceIn(0, input.lastIndex)
            val frac = srcPos - idx0
            val sampleA = input[idx0] * (1.0 - frac) + input[idx1] * frac

            // Crossfade near grain boundaries to suppress splice clicks
            val window = when {
                phaseInGrain < 64 -> phaseInGrain / 64.0
                phaseInGrain > grainSize - 64 -> (grainSize - phaseInGrain) / 64.0
                else -> 1.0
            }.coerceIn(0.0, 1.0)
            resampled[i] = (sampleA * (0.35 + 0.65 * window)).roundToInt().coerceIn(-32767, 32767).toShort()
        }

        // Gentle low-pass smoothing
        val lpAlpha = 0.38f
        var prev = 0f
        for (i in resampled.indices) {
            prev += lpAlpha * (resampled[i].toFloat() - prev)
            resampled[i] = prev.roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return normalize(resampled, 0.90f)
    }

    private fun applyTapeHiss(input: ShortArray, sampleRate: Int): ShortArray {
        val out = ShortArray(input.size)
        val rng = Random(2026)
        val lpAlpha = 0.42f
        var prev = 0f

        for (i in input.indices) {
            val t = i.toDouble() / sampleRate
            // Subtle tape flutter
            val flutterOffset = (sin(2.0 * PI * 3.8 * t) * 4.0).toInt()
            val srcIdx = (i + flutterOffset).coerceIn(0, input.lastIndex)
            val normalized = input[srcIdx] / 32768f

            // Analog tanh saturation
            val saturated = tanh(normalized * 2.1f) * 0.72f
            // Low-pass cassette roll-off
            prev += lpAlpha * (saturated - prev)
            // Cassette noise floor
            val hiss = (rng.nextFloat() * 2f - 1f) * 0.022f
            out[i] = ((prev + hiss) * 32767f).roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return normalize(out, 0.88f)
    }

    private fun applyRadioBandpass(input: ShortArray, sampleRate: Int): ShortArray {
        val out = ShortArray(input.size)
        val rng = Random(909)
        // High-pass (~300Hz) followed by low-pass (~2.5kHz) IIR cascade
        val hpAlpha = 0.91f
        val lpAlpha = 0.34f
        var prevIn = 0f
        var hpOut = 0f
        var lpOut = 0f

        for (i in input.indices) {
            val x = input[i] / 32768f
            hpOut = hpAlpha * (hpOut + x - prevIn)
            prevIn = x
            lpOut += lpAlpha * (hpOut - lpOut)

            // Overdrive transceiver compression + static crackle
            val compressed = tanh(lpOut * 3.2f) * 0.78f
            val crackle = if (rng.nextFloat() < 0.015f) (rng.nextFloat() * 2f - 1f) * 0.09f else (rng.nextFloat() * 2f - 1f) * 0.015f
            out[i] = ((compressed + crackle) * 32767f).roundToInt().coerceIn(-32767, 32767).toShort()
        }
        return normalize(out, 0.92f)
    }

    fun writeWavFile(samples: ShortArray, sampleRate: Int = SAMPLE_RATE, targetFile: File): File {
        val dataSize = samples.size * 2
        val byteRate = sampleRate * 2
        BufferedOutputStream(FileOutputStream(targetFile)).use { out ->
            // RIFF header
            out.write(byteArrayOf('R'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 'F'.code.toByte()))
            out.writeIntLE(36 + dataSize)
            out.write(byteArrayOf('W'.code.toByte(), 'A'.code.toByte(), 'V'.code.toByte(), 'E'.code.toByte()))
            // fmt chunk
            out.write(byteArrayOf('f'.code.toByte(), 'm'.code.toByte(), 't'.code.toByte(), ' '.code.toByte()))
            out.writeIntLE(16) // PCM chunk size
            out.writeShortLE(1) // PCM format = 1
            out.writeShortLE(1) // Mono = 1
            out.writeIntLE(sampleRate)
            out.writeIntLE(byteRate)
            out.writeShortLE(2) // Block align
            out.writeShortLE(16) // Bits per sample
            // data chunk
            out.write(byteArrayOf('d'.code.toByte(), 'a'.code.toByte(), 't'.code.toByte(), 'a'.code.toByte()))
            out.writeIntLE(dataSize)
            val buf = ByteArray(4096)
            var offset = 0
            for (sample in samples) {
                val v = sample.toInt()
                buf[offset++] = (v and 0xFF).toByte()
                buf[offset++] = ((v ushr 8) and 0xFF).toByte()
                if (offset == buf.size) {
                    out.write(buf, 0, offset)
                    offset = 0
                }
            }
            if (offset > 0) {
                out.write(buf, 0, offset)
            }
        }
        return targetFile
    }

    private fun BufferedOutputStream.writeIntLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 24) and 0xFF)
    }

    private fun BufferedOutputStream.writeShortLE(value: Int) {
        write(value and 0xFF)
        write((value ushr 8) and 0xFF)
    }
}
