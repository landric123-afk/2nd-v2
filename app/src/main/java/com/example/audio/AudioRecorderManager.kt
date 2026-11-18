package com.example.audio

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import kotlin.math.sqrt

/**
 * Captures real 16-bit PCM microphone audio via [AudioRecord], applies [AudioDspProcessor]
 * voice modulation / lo-fi presets on demand into `.wav` files, and plays either local files
 * or remote HTTPS Firebase Storage audio URLs via [MediaPlayer].
 */
class AudioRecorderManager(context: Context) {

    private val appContext = context.applicationContext
    private val dropsDir: File by lazy {
        File(appContext.filesDir, "sound_drops").apply { mkdirs() }
    }

    private val ioScope = CoroutineScope(Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var captureJob: Job? = null
    private val drySamples = Collections.synchronizedList(mutableListOf<Short>())

    @Volatile
    private var latestAmplitude: Float = 0.08f

    private var cachedDryPcm: ShortArray? = null
    private var mediaPlayer: MediaPlayer? = null
    private var activeRenderedFile: File? = null

    fun hasRecordPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Starts real PCM microphone capture at 22.05 kHz mono if `RECORD_AUDIO` permission is granted.
     * Returns true if hardware AudioRecord started, or false if unavailable/denied.
     */
    @SuppressLint("MissingPermission")
    fun startRecording(): Boolean {
        if (!hasRecordPermission()) return false
        stopCaptureInternal()
        drySamples.clear()
        cachedDryPcm = null

        val minBuf = AudioRecord.getMinBufferSize(
            AudioDspProcessor.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val bufferSize = (minBuf * 2).coerceAtLeast(4096)

        return try {
            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                AudioDspProcessor.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                return false
            }
            recorder.startRecording()
            audioRecord = recorder
            latestAmplitude = 0.1f

            captureJob = ioScope.launch {
                val readBuf = ShortArray(bufferSize / 2)
                while (isActive) {
                    val activeRec = audioRecord ?: break
                    val readCount = activeRec.read(readBuf, 0, readBuf.size)
                    if (readCount > 0) {
                        var sumSq = 0.0
                        synchronized(drySamples) {
                            // Cap buffer around 90 seconds max to protect heap
                            val maxSamples = AudioDspProcessor.SAMPLE_RATE * 90
                            val remaining = maxSamples - drySamples.size
                            val toCopy = readCount.coerceAtMost(remaining.coerceAtLeast(0))
                            for (i in 0 until toCopy) {
                                val s = readBuf[i]
                                drySamples.add(s)
                                sumSq += s.toDouble() * s.toDouble()
                            }
                        }
                        if (readCount > 0) {
                            val rms = sqrt(sumSq / readCount).toFloat()
                            latestAmplitude = (rms / 12000f).coerceIn(0.08f, 1.0f)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Hardware AudioRecord unavailable, falling back to sim capture: ${e.message}")
            stopCaptureInternal()
            false
        }
    }

    fun pollNormalizedAmplitude(): Float = latestAmplitude

    /**
     * Stops microphone capture and renders the initial `.wav` clip using [preset].
     */
    fun stopAndFinalizeRecording(preset: VoiceFilterPreset = VoiceFilterPreset.RAW): String? {
        stopCaptureInternal()
        val captured = synchronized(drySamples) {
            if (drySamples.isNotEmpty()) drySamples.toShortArray() else null
        }
        val dry = captured ?: AudioDspProcessor.generateFallbackBeaconPcm(durationSeconds = 3)
        cachedDryPcm = dry
        return renderWithPreset(preset)
    }

    /**
     * Re-renders the captured dry voice clip with a new [VoiceFilterPreset] for preview or broadcast.
     */
    fun renderWithPreset(preset: VoiceFilterPreset): String? {
        val dry = cachedDryPcm ?: return activeRenderedFile?.absolutePath
        return try {
            val processed = AudioDspProcessor.applyPreset(dry, AudioDspProcessor.SAMPLE_RATE, preset)
            val outFile = File(dropsDir, "drop_${System.currentTimeMillis()}_${preset.id.lowercase()}.wav")
            AudioDspProcessor.writeWavFile(processed, AudioDspProcessor.SAMPLE_RATE, outFile)
            activeRenderedFile?.let { prev ->
                if (prev.absolutePath != outFile.absolutePath) {
                    try { prev.delete() } catch (_: Throwable) {}
                }
            }
            activeRenderedFile = outFile
            outFile.absolutePath
        } catch (e: Exception) {
            Log.w(TAG, "Failed rendering DSP preset ${preset.id}: ${e.message}")
            null
        }
    }

    fun discardRecording(existingPath: String? = null) {
        stopCaptureInternal()
        drySamples.clear()
        cachedDryPcm = null
        activeRenderedFile?.let { f ->
            try { f.delete() } catch (_: Throwable) {}
        }
        activeRenderedFile = null
        if (!existingPath.isNullOrBlank()) {
            try {
                File(existingPath).delete()
            } catch (_: Throwable) {
            }
        }
    }

    private fun stopCaptureInternal() {
        captureJob?.cancel()
        captureJob = null
        audioRecord?.let { rec ->
            try {
                if (rec.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    rec.stop()
                }
            } catch (_: Throwable) {
            }
            try {
                rec.release()
            } catch (_: Throwable) {
            }
        }
        audioRecord = null
    }

    /**
     * Plays either a local `.wav`/`.m4a` file path or a remote HTTPS Firebase Storage download URL.
     */
    fun startPlayback(
        sourcePathOrUrl: String?,
        onCompletion: () -> Unit
    ): Boolean {
        stopPlayback()
        if (sourcePathOrUrl.isNullOrBlank()) return false

        val isRemoteUrl = sourcePathOrUrl.startsWith("http://") || sourcePathOrUrl.startsWith("https://")
        if (!isRemoteUrl) {
            val file = File(sourcePathOrUrl)
            if (!file.exists() || file.length() <= 0L) return false
        }

        return try {
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(sourcePathOrUrl)
                setOnCompletionListener {
                    stopPlayback()
                    onCompletion()
                }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer async error $what/$extra for $sourcePathOrUrl")
                    stopPlayback()
                    onCompletion()
                    true
                }
                if (isRemoteUrl) {
                    setOnPreparedListener { mp -> mp.start() }
                    prepareAsync()
                } else {
                    prepare()
                    start()
                }
            }
            mediaPlayer = player
            true
        } catch (e: Exception) {
            Log.w(TAG, "MediaPlayer failed for $sourcePathOrUrl: ${e.message}")
            stopPlayback()
            false
        }
    }

    fun getPlaybackFraction(): Float {
        val player = mediaPlayer ?: return 0f
        return try {
            val duration = player.duration
            if (duration > 0) (player.currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f) else 0f
        } catch (_: Exception) {
            0f
        }
    }

    fun getFileDurationSeconds(audioPath: String?): Int {
        if (audioPath.isNullOrBlank()) return 0
        val file = File(audioPath)
        if (!file.exists()) return 0
        val player = MediaPlayer()
        return try {
            player.setDataSource(file.absolutePath)
            player.prepare()
            (player.duration / 1000).coerceAtLeast(1)
        } catch (_: Exception) {
            0
        } finally {
            try {
                player.release()
            } catch (_: Throwable) {
            }
        }
    }

    fun stopPlayback() {
        mediaPlayer?.let { player ->
            try {
                if (player.isPlaying) player.stop()
            } catch (_: Throwable) {
            }
            try {
                player.reset()
                player.release()
            } catch (_: Throwable) {
            }
        }
        mediaPlayer = null
    }

    fun release() {
        stopCaptureInternal()
        stopPlayback()
    }

    private companion object {
        const val TAG = "AudioRecorderManager"
    }
}
