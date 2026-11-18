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
        // 9.2.0 (Nova): a recording just finished — give the
        // storage guard its pass. The stop lands asynchronously,
        // so this recording may still read as in-progress in this
        // pass and only become deletable on the next one (app
        // start / recordings visit); older recordings over the
        // cap are still collected here.
        enforceRecordingStorageCap(context)
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

    /** The PendingIntent that fires [RecordStartReceiver] for one
     * scheduled recording. One request code per programme (channel
     * + start), so scheduling, cancelling and firing all address
     * the same alarm. */
    private fun recordStartPendingIntent(
        context: Context,
        scheduled: PlaylistRepository.ScheduledRecording
    ): PendingIntent {
        val intent = Intent(context, RecordStartReceiver::class.java).apply {
            putExtra(RecordStartReceiver.EXTRA_CHANNEL_ID, scheduled.channelId)
            putExtra(RecordStartReceiver.EXTRA_CHANNEL_NAME, scheduled.channelName)
            putExtra(RecordStartReceiver.EXTRA_CHANNEL_URL, scheduled.channelUrl)
            putExtra(RecordStartReceiver.EXTRA_START_MILLIS, scheduled.startMillis)
            putExtra(RecordStartReceiver.EXTRA_STOP_MILLIS, scheduled.stopMillis)
        }
        return PendingIntent.getBroadcast(
            context,
            (scheduled.channelId + "@" + scheduled.startMillis).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Combine (8.2.0 Nova + 8.3.1 Juniper): schedule a recording
     * to START at a programme's start time. Fires
     * [RecordStartReceiver], which starts the recording and arms
     * the matching stop. Uses setAlarmClock, not
     * setAndAllowWhileIdle: an alarm-clock alarm is exact (the
     * recording starts on time instead of a few minutes late) AND
     * it grants the app a temporary exemption from Android 12's
     * background foreground-service restriction. The receiver has
     * to start the foreground RecordingService while the app is
     * closed, and a plain inexact alarm does not grant that — the
     * start threw ForegroundServiceStartNotAllowedException and the
     * recording silently never happened. setAlarmClock needs no
     * SCHEDULE_EXACT_ALARM permission.
     */
    fun scheduleRecordingStart(
        context: Context,
        scheduled: PlaylistRepository.ScheduledRecording
    ) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.setAlarmClock(
            AlarmManager.AlarmClockInfo(scheduled.startMillis, null),
            recordStartPendingIntent(context, scheduled)
        )
    }

    /**
     * Combine (8.3.1 Juniper, adapted to Nova's store): re-arm
     * the start alarms for every upcoming scheduled recording.
     * Alarms do not survive a reboot, so the boot receiver calls
     * this — without it a schedule made before a reboot would sit
     * in the list looking scheduled and never fire. Returns how
     * many alarms were re-armed. (The BootReceiver's manifest
     * registration is foundation work — see the combine notes.)
     */
    fun rearmScheduledRecordingAlarms(context: Context): Int {
        val upcoming = PlaylistRepository.scheduledRecordings(context)
        for (recording in upcoming) scheduleRecordingStart(context, recording)
        return upcoming.size
    }

    /** 8.2.0 (Nova): cancel a scheduled recording's start alarm. */
    fun cancelRecordingStart(
        context: Context,
        scheduled: PlaylistRepository.ScheduledRecording
    ) {
        val pending = recordStartPendingIntent(context, scheduled)
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

    /**
     * 9.2.0 (Nova): the recordings storage guard. When the user
     * has set a cap (AppSettings, default off) and recordings on
     * disk exceed it, delete the OLDEST completed recordings
     * until back under the cap. Two recordings are untouchable:
     * anything still recording, and the single most recent
     * recording — the guard never leaves the user with nothing,
     * and never pulls a download out from under the recorder.
     * Runs at app start (MainActivity), when a recording stops,
     * and when the Recordings screen loads. Returns the bytes
     * freed; a run that freed anything leaves a note the
     * Recordings screen shows once ("Freed X by auto-delete").
     * With the cap off this is one pref read and nothing else —
     * in particular it never builds the DownloadManager.
     *
     * 9.2.1 (Nova): a third untouchable — recordings of channels
     * that are CURRENT favorites. A recording's download id is
     * derived from its channel id (Recording.idForChannel), so
     * the protected set is the favorites mapped through the same
     * derivation. If a pass ends still over the cap because the
     * remaining candidates were favorites, it records that in
     * AppSettings and the Recordings screen's note says so.
     */
    @Synchronized
    fun enforceRecordingStorageCap(context: Context): Long {
        val cap = AppSettings.recordingAutoDeleteCapBytes(context)
        if (cap <= 0L) {
            AppSettings.setLastAutoDeleteBlocked(context, false)
            return 0L
        }
        val recordings = listRecordings(context)
        var total = recordings.sumOf { it.bytesDownloaded }
        if (total <= cap) {
            AppSettings.setLastAutoDeleteBlocked(context, false)
            return 0L
        }
        // listRecordings is newest-first: index 0 is the most
        // recent recording and is never deleted.
        val newestId = recordings.firstOrNull()?.id
        // 9.2.1 (Nova): favorite channels' recordings are
        // protected — compare download ids, which are derived
        // from channel ids by the same function the recorder
        // uses, so the mapping is exact.
        val protectedIds = PlaylistRepository.favorites(context)
            .map { Recording.idForChannel(it) }
            .toSet()
        var freed = 0L
        var skippedProtected = false
        for (recording in recordings.asReversed()) {
            if (total <= cap) break
            if (recording.inProgress || recording.id == newestId) continue
            if (recording.id in protectedIds) {
                skippedProtected = true
                continue
            }
            deleteRecording(context, recording.id)
            // A deleted recording's resume point goes with it,
            // exactly as on the manual delete path.
            PlaylistRepository.clearRecordingProgress(context, recording.id)
            total -= recording.bytesDownloaded
            freed += recording.bytesDownloaded
        }
        if (freed > 0L) {
            AppSettings.setLastAutoFreedBytes(context, freed)
        }
        // Still over the cap with favorites in the way: the
        // Recordings note reports the hold-back once.
        AppSettings.setLastAutoDeleteBlocked(
            context, blocked = total > cap && skippedProtected
        )
        return freed
    }

    fun formatBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
        else -> "$bytes B"
    }
}
