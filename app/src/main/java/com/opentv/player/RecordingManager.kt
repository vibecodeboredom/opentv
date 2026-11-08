package com.opentv.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import java.io.File
import java.util.concurrent.Executors

/**
 * Owns the media3 DownloadManager used for local recordings.
 *
 * Recording a live channel = downloading its HLS/DASH stream into the app's
 * own storage. Stopping a recording pauses the download (stop reason) so the
 * downloaded segments are kept and stay playable from the cache. Deleting a
 * recording removes the download and its data.
 *
 * The download cache doubles as the playback cache: a recording is played by
 * building ExoPlayer with [cacheDataSourceFactory] and playing the same URI,
 * so it reads from disk instead of the network.
 */
@UnstableApi
object RecordingManager {

    private const val STOP_REASON_USER_STOPPED = 1
    private const val MAX_CACHE_BYTES = 8L * 1024 * 1024 * 1024 // 8 GB

    private var downloadManager: DownloadManager? = null
    private var cache: SimpleCache? = null
    private var databaseProvider: StandaloneDatabaseProvider? = null

    private fun dbProvider(context: Context): StandaloneDatabaseProvider {
        return databaseProvider ?: StandaloneDatabaseProvider(context).also { databaseProvider = it }
    }

    /** The shared download cache (recordings live here). */
    @Synchronized
    fun getCache(context: Context): SimpleCache {
        return cache ?: SimpleCache(
            File(context.getExternalFilesDir(null), "recordings"),
            LeastRecentlyUsedCacheEvictor(MAX_CACHE_BYTES),
            dbProvider(context)
        ).also { cache = it }
    }

    /** The DownloadManager; also starts the foreground RecordingService. */
    @Synchronized
    fun getDownloadManager(context: Context): DownloadManager {
        return downloadManager ?: DownloadManager(
            context,
            dbProvider(context),
            getCache(context),
            DefaultHttpDataSource.Factory(),
            Executors.newFixedThreadPool(4)
        ).also {
            downloadManager = it
            // Keep downloads running while the app is in the background.
            DownloadService.start(context, RecordingService::class.java)
        }
    }

    /** DataSource factory that reads recordings from the download cache. */
    fun cacheDataSourceFactory(context: Context): CacheDataSource.Factory =
        CacheDataSource.Factory()
            .setCache(getCache(context))
            .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Start recording a channel. No-op if it is already recording. */
    @Synchronized
    fun startRecording(context: Context, channel: Channel) {
        val manager = getDownloadManager(context)
        val id = Recording.idForChannel(channel.id)
        val existing = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull()
        if (existing != null && existing.state != Download.STATE_FAILED) {
            // Already have this recording (in progress or paused): resume it.
            if (existing.request.stopReason != DownloadRequest.STOP_REASON_NONE) {
                manager.addDownload(existing.request.copyWithStopReason(DownloadRequest.STOP_REASON_NONE))
            }
            return
        }
        val mimeType = when {
            channel.url.contains(".m3u8", ignoreCase = true) -> MimeTypes.APPLICATION_M3U8
            channel.url.contains(".mpd", ignoreCase = true) -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        val meta = "${channel.name}\n${System.currentTimeMillis()}".toByteArray(Charsets.UTF_8)
        val request = DownloadRequest.Builder(id, Uri.parse(channel.url))
            .setMimeType(mimeType)
            .setData(meta)
            .build()
        manager.addDownload(request)
    }

    /** Stop an in-progress recording but KEEP the downloaded data. */
    @Synchronized
    fun stopRecording(context: Context, channelId: String) {
        val manager = getDownloadManager(context)
        val id = Recording.idForChannel(channelId)
        val download = runCatching { manager.downloadIndex.getDownload(id) }.getOrNull()
            ?: return
        if (download.state == Download.STATE_COMPLETED) return
        manager.addDownload(download.request.copyWithStopReason(STOP_REASON_USER_STOPPED))
    }

    /** True while a channel's recording is actively downloading. */
    fun isRecording(context: Context, channelId: String): Boolean {
        val manager = getDownloadManager(context)
        val download = runCatching {
            manager.downloadIndex.getDownload(Recording.idForChannel(channelId))
        }.getOrNull() ?: return false
        return download.state == Download.STATE_DOWNLOADING ||
            download.state == Download.STATE_QUEUED ||
            download.state == Download.STATE_RESTARTING
    }

    /** True if a channel has a saved recording (finished or stopped). */
    fun hasRecording(context: Context, channelId: String): Boolean {
        val manager = getDownloadManager(context)
        val download = runCatching {
            manager.downloadIndex.getDownload(Recording.idForChannel(channelId))
        }.getOrNull() ?: return false
        return download.state == Download.STATE_COMPLETED ||
            download.state == Download.STATE_STOPPED
    }

    /** Delete a recording and its downloaded data. */
    @Synchronized
    fun deleteRecording(context: Context, recordingId: String) {
        getDownloadManager(context).removeDownload(recordingId)
    }

    /** All recordings, newest first. */
    fun listRecordings(context: Context): List<Recording> {
        val manager = getDownloadManager(context)
        val out = mutableListOf<Recording>()
        val cursor = runCatching { manager.downloadIndex.getDownloads() }.getOrNull()
            ?: return emptyList()
        cursor.use {
            while (it.moveToNext()) {
                val download = it.download
                val meta = String(download.request.data, Charsets.UTF_8).lines()
                val name = meta.getOrNull(0)?.ifBlank { download.request.id } ?: download.request.id
                val started = meta.getOrNull(1)?.toLongOrNull() ?: 0L
                out += Recording(
                    id = download.request.id,
                    channelName = name,
                    uri = download.request.uri.toString(),
                    startedAtMillis = started,
                    bytesDownloaded = download.bytesDownloaded,
                    inProgress = download.state == Download.STATE_DOWNLOADING ||
                        download.state == Download.STATE_QUEUED ||
                        download.state == Download.STATE_RESTARTING
                )
            }
        }
        return out.sortedByDescending { it.startedAtMillis }
    }

    /** Total bytes used by recordings on disk. */
    fun totalBytesUsed(context: Context): Long =
        listRecordings(context).sumOf { it.bytesDownloaded }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
