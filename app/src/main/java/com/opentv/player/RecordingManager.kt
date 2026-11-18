package com.opentv.player

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
        if (existing != null) {
            when (existing.state) {
                Download.STATE_DOWNLOADING, Download.STATE_QUEUED, Download.STATE_RESTARTING -> return
                Download.STATE_STOPPED -> {
                    // Paused recording: clear its stop reason to resume it.
                    DownloadService.sendSetStopReason(
                        context, RecordingService::class.java, id,
                        Download.STOP_REASON_NONE, false
                    )
                    return
                }
                Download.STATE_COMPLETED, Download.STATE_FAILED -> {
                    // Re-record over the old one: drop it first.
                    manager.removeDownload(id)
                }
                else -> return
            }
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
        val id = Recording.idForChannel(channelId)
        DownloadService.sendSetStopReason(
            context, RecordingService::class.java, id,
            STOP_REASON_USER_STOPPED, false
        )
    }

    /**
     * Schedule an automatic stop for a recording (custom recording).
     * Fires [RecordStopReceiver] at [stopAtMillis]; no exact-alarm
     * permission needed (inexact, may land a few minutes late).
     */
    fun scheduleStopRecording(context: Context, channelId: String, stopAtMillis: Long) {
        val intent = Intent(context, RecordStopReceiver::class.java).apply {
            putExtra(RecordStopReceiver.EXTRA_CHANNEL_ID, channelId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            channelId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, stopAtMillis, pending)
    }

    /**
     * 8.3.1 — Schedule a recording to START at a future programme's
     * start time. Fires [RecordStartReceiver] at
     * [recording.startMillis]; the receiver starts the recording and
     * schedules its stop. Uses setAlarmClock, not
     * setAndAllowWhileIdle: an alarm-clock alarm is exact (the
     * recording starts on time instead of a few minutes late) AND
     * it grants the app a temporary exemption from Android 12's
     * background foreground-service restriction. The receiver has
     * to start the foreground RecordingService while the app is
     * closed, and a plain inexact alarm does not grant that — the
     * start threw ForegroundServiceStartNotAllowedException and the
     * recording silently never happened. setAlarmClock needs no
     * SCHEDULE_EXACT_ALARM permission. The request code comes from
     * the schedule's id, so two programmes on one channel never
     * share an alarm.
     */
    fun scheduleRecording(context: Context, recording: ScheduledRecordings.ScheduledRecording) {
        val intent = Intent(context, RecordStartReceiver::class.java).apply {
            putExtra(RecordStartReceiver.EXTRA_SCHEDULED_ID, recording.id)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            recording.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAlarmClock(
            AlarmManager.AlarmClockInfo(recording.startMillis, null),
            pending
        )
    }

    /**
     * 8.3.1 — Re-arm the start alarms for every upcoming scheduled
     * recording. Alarms do not survive a reboot, so the boot
     * receiver calls this — without it a schedule made before a
     * reboot would sit in the list looking scheduled and never
     * fire. Returns how many alarms were re-armed.
     */
    fun rearmScheduledRecordingAlarms(context: Context): Int {
        val upcoming = PlaylistRepository.scheduledRecordings(context)
        for (recording in upcoming) scheduleRecording(context, recording)
        return upcoming.size
    }

    /** 8.3.0 — Cancel a scheduled recording's start alarm. The
     * stored schedule itself is removed by
     * PlaylistRepository.removeScheduledRecording. */
    fun cancelScheduledRecording(context: Context, recordingId: String) {
        val intent = Intent(context, RecordStartReceiver::class.java).apply {
            putExtra(RecordStartReceiver.EXTRA_SCHEDULED_ID, recordingId)
        }
        val pending = PendingIntent.getBroadcast(
            context,
            recordingId.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.cancel(pending)
        pending.cancel()
    }

    /**
     * 8.3.0 — Delete the finished recordings older than [keepDays]
     * days (see [RecordingLibrary.oldRecordings]), returning the
     * ones deleted. The recordings screen calls this with the list
     * it already fetched, so the DownloadIndex is not queried twice
     * for one screen load — and the caller drops the returned ids
     * from its fetched list instead of re-querying.
     */
    fun deleteOldRecordings(
        context: Context,
        recordings: List<Recording>,
        keepDays: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<Recording> {
        val old = RecordingLibrary.oldRecordings(recordings, keepDays, nowMillis)
        for (recording in old) {
            deleteRecording(context, recording.id)
        }
        return old
    }

    /**
     * 8.3.0 — The same pruning, fetching the list first — for
     * callers that do not have one (the scheduled-record start
     * receiver, which runs in the background). Returns how many
     * were deleted.
     */
    fun pruneOldRecordings(
        context: Context,
        keepDays: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): Int = deleteOldRecordings(context, listRecordings(context), keepDays, nowMillis).size

    /** True while a channel's recording is actively downloading.
     * 6.1.0: does NOT create the DownloadManager. updateTransportIcons
     * calls this on every play/pause change and overlay open, and the
     * old code built the whole DownloadManager + SimpleCache + the
     * foreground RecordingService on the first such check, even when
     * the user never records. An active recording implies the manager
     * exists in this process (the service created it when the
     * recording started), so a null manager means not recording. */
    fun isRecording(context: Context, channelId: String): Boolean {
        val manager = downloadManager ?: return false
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

    /**
     * 8.3.1 — Actual bytes the recordings occupy on disk. The
     * DownloadIndex's bytesDownloaded can overstate it: the cache's
     * LRU evictor may drop segments of old recordings once the
     * cache passes its cap, and the index never learns. The cache
     * holds only recording downloads (playback never writes to
     * it), so its on-disk size is the true total — use this for
     * the totals the user sees. Per-recording sizes still come
     * from the index; after eviction a single card can claim
     * slightly more than its share on disk.
     */
    fun diskBytesUsed(context: Context): Long = getCache(context).cacheSpace

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
