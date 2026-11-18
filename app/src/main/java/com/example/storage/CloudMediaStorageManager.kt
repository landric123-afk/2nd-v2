package com.example.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.storage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class UploadedMediaResult(
    val downloadUrl: String,
    val storagePath: String
)

/**
 * Handles Firebase Cloud Storage uploads for recorded audio clips, stencil images, and
 * AR-pinned PDFs, plus cached local downloads for remote PDF rendering via Android `PdfRenderer`.
 */
class CloudMediaStorageManager(context: Context) {

    private val appContext = context.applicationContext
    private val storage by lazy { Firebase.storage }
    private val httpClient = OkHttpClient()
    private val pdfCacheDir: File by lazy {
        File(appContext.cacheDir, "ar_pdf_cache").apply { mkdirs() }
    }

    suspend fun uploadAudioClip(
        userId: String,
        localFile: File,
        dropKey: String
    ): Result<UploadedMediaResult> {
        if (userId.isBlank() || !localFile.exists() || localFile.length() <= 0L) {
            return Result.failure(IllegalArgumentException("Audio clip missing or empty"))
        }
        if (localFile.length() > MAX_UPLOAD_BYTES) {
            return Result.failure(IllegalArgumentException("Audio clip exceeds 12 MB upload limit"))
        }
        val ext = localFile.extension.ifBlank { "wav" }.lowercase()
        val contentType = if (ext == "m4a" || ext == "mp4") "audio/mp4" else "audio/wav"
        val storagePath = "sound_drops/$userId/$dropKey.$ext"

        return try {
            val ref = storage.reference.child(storagePath)
            val metadata = StorageMetadata.Builder()
                .setContentType(contentType)
                .build()
            ref.putFile(Uri.fromFile(localFile), metadata).await()
            val url = ref.downloadUrl.await().toString()
            Result.success(UploadedMediaResult(downloadUrl = url, storagePath = storagePath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud Storage audio upload failed ($storagePath): ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun uploadGraffitiMedia(
        userId: String,
        contentUri: Uri,
        mediaType: String,
        graffitiKey: String
    ): Result<UploadedMediaResult> = withContext(Dispatchers.IO) {
        if (userId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Authenticated user required"))
        }
        val resolvedType = mediaType.uppercase()
        val ext = if (resolvedType == "PDF") "pdf" else "jpg"
        val mime = if (resolvedType == "PDF") "application/pdf" else "image/jpeg"
        val storagePath = "graffiti_media/$userId/$graffitiKey.$ext"

        try {
            val bytes = appContext.contentResolver.openInputStream(contentUri)?.use { stream ->
                val data = stream.readBytes()
                if (data.size > MAX_UPLOAD_BYTES) {
                    return@withContext Result.failure(IllegalArgumentException("Media exceeds 12 MB limit"))
                }
                data
            } ?: return@withContext Result.failure(IllegalStateException("Unable to read selected file"))

            val ref = storage.reference.child(storagePath)
            val metadata = StorageMetadata.Builder()
                .setContentType(mime)
                .build()
            ref.putBytes(bytes, metadata).await()
            val url = ref.downloadUrl.await().toString()
            Result.success(UploadedMediaResult(downloadUrl = url, storagePath = storagePath))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Cloud Storage media upload failed ($storagePath): ${e.message}")
            Result.failure(e)
        }
    }

    suspend fun copyPdfToCache(uriOrUrl: String, cacheKey: String): File? = withContext(Dispatchers.IO) {
        val safeName = cacheKey.replace(Regex("[^a-zA-Z0-9_\\-]"), "_") + ".pdf"
        val target = File(pdfCacheDir, safeName)
        if (target.exists() && target.length() > 0L) return@withContext target

        return@withContext try {
            if (uriOrUrl.startsWith("http://") || uriOrUrl.startsWith("https://")) {
                val req = Request.Builder().url(uriOrUrl).build()
                httpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    val body = resp.body ?: return@withContext null
                    target.outputStream().use { out -> body.byteStream().copyTo(out) }
                    target
                }
            } else {
                val uri = Uri.parse(uriOrUrl)
                appContext.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { out -> input.copyTo(out) }
                }
                if (target.exists() && target.length() > 0L) target else null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed preparing PDF for rendering: ${e.message}")
            null
        }
    }

    private companion object {
        const val TAG = "CloudMediaStorage"
        const val MAX_UPLOAD_BYTES = 12 * 1024 * 1024L
    }
}
