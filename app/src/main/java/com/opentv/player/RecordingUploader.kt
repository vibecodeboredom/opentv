package com.opentv.player

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.TransformationRequest
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Uploads a finished recording to a server the user configured
 * (Settings → Recording upload URL).
 *
 * The recording lives in media3's download cache as HLS/DASH segments, so
 * it is first exported to a single .mp4 with media3 Transformer (reading
 * from the cache, H.264/AAC output so typical IPTV streams are remuxed
 * without re-encoding), then sent with an HTTP PUT to
 * `<uploadUrl>/<channel>_<date>.mp4`. Basic auth is supported via the URL
 * (`https://user:pass@host/path`).
 *
 * Note: this has not been verified on a real TV yet (no device access) —
 * the export of a paused live recording is the part most likely to need
 * tuning once it can be tried on hardware.
 */
@OptIn(UnstableApi::class)
object RecordingUploader {

    /** Upload a recording. Returns the destination URL on success. */
    suspend fun upload(context: Context, recording: Recording): Result<String> =
        withContext(Dispatchers.IO) {
            val baseUrl = PlaylistRepository.uploadUrl(context)
            if (baseUrl.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("No upload URL configured")
                )
            }
            val exportFile = File(context.cacheDir, "upload-${recording.id}.mp4")
            val export = exportToFile(context, recording, exportFile)
            if (export.isFailure) {
                exportFile.delete()
                return@withContext Result.failure(export.exceptionOrNull()!!)
            }
            try {
                val destUrl = putFile(baseUrl, recording, exportFile)
                Result.success(destUrl)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                exportFile.delete()
            }
        }

    private suspend fun exportToFile(
        context: Context,
        recording: Recording,
        outFile: File
    ): Result<Unit> = suspendCancellableCoroutine { cont ->
        val handlerThread = HandlerThread("opentv-export").apply { start() }
        val looper = handlerThread.looper
        val request = TransformationRequest.Builder()
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .build()
        val transformer = Transformer.Builder(context)
            .setAssetLoaderFactory(
                DefaultAssetLoaderFactory(
                    context,
                    RecordingManager.cacheDataSourceFactory(context)
                )
            )
            .setTransformationRequest(request)
            .setLooper(looper)
            .setListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handlerThread.quitSafely()
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handlerThread.quitSafely()
                    if (cont.isActive) cont.resume(Result.failure(exportException))
                }
            })
            .build()
        cont.invokeOnCancellation {
            transformer.cancel()
            handlerThread.quitSafely()
        }
        Handler(looper).post {
            transformer.start(MediaItem.fromUri(recording.uri), outFile.absolutePath)
        }
    }

    private fun putFile(baseUrl: String, recording: Recording, file: File): String {
        val safeName = recording.channelName
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
            .take(60)
            .ifBlank { "recording" }
        val date = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US)
            .format(Date(recording.startedAtMillis))
        val filename = "${safeName}_$date.mp4"
        val base = baseUrl.trimEnd('/')
        val url = URL("$base/$filename")
        val conn = url.openConnection() as HttpURLConnection
        url.userInfo?.let { userInfo ->
            val encoded = Base64.encodeToString(
                userInfo.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )
            conn.setRequestProperty("Authorization", "Basic $encoded")
        }
        conn.requestMethod = "PUT"
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "video/mp4")
        conn.setFixedLengthStreamingMode(file.length())
        conn.connectTimeout = 15_000
        conn.readTimeout = 120_000
        file.inputStream().use { input ->
            conn.outputStream.use { output -> input.copyTo(output) }
        }
        val code = conn.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("Upload failed: HTTP $code")
        }
        return "$base/$filename"
    }
}
