package com.opentv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

/**
 * 8.2.0 (Nova): fired by the AlarmManager when a scheduled
 * recording's programme starts. Starts the recording from the
 * channel snapshot stored with the schedule (id / name / URL), arms
 * the automatic stop at the programme's end via the existing
 * [RecordingManager.scheduleStopRecording], and retires the
 * schedule entry — from here on it is an ordinary recording.
 *
 * Everything runs inside runCatching: an alarm that lands when the
 * download machinery cannot start (storage gone, service blocked)
 * must never crash the process the alarm woke up. A programme that
 * already ended while the TV was off is skipped entirely.
 */
@OptIn(UnstableApi::class)
class RecordStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
        val startMillis = intent.getLongExtra(EXTRA_START_MILLIS, 0L)
        val stopMillis = intent.getLongExtra(EXTRA_STOP_MILLIS, 0L)
        val appContext = context.applicationContext
        // Retire the schedule first: whatever happens next, this
        // entry has fired and must not fire or be listed again.
        PlaylistRepository.removeScheduledRecording(appContext, channelId, startMillis)
        if (stopMillis <= System.currentTimeMillis()) return
        val name = intent.getStringExtra(EXTRA_CHANNEL_NAME) ?: return
        val url = intent.getStringExtra(EXTRA_CHANNEL_URL) ?: return
        runCatching {
            val channel = Channel(
                id = channelId,
                name = name,
                url = url,
                group = "",
                logo = null,
                tvgId = null
            )
            RecordingManager.startRecording(appContext, channel)
            RecordingManager.scheduleStopRecording(appContext, channelId, stopMillis)
            // Combine (Juniper 8.3.0): the recording infra is awake
            // anyway — enforce the age-based auto-delete setting
            // while we are here.
            val keepDays = AppSettings.autoDeleteRecordingsDays(appContext)
            if (keepDays > 0) RecordingManager.pruneOldRecordings(appContext, keepDays)
        }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_CHANNEL_NAME = "channel_name"
        const val EXTRA_CHANNEL_URL = "channel_url"
        const val EXTRA_START_MILLIS = "start_millis"
        const val EXTRA_STOP_MILLIS = "stop_millis"
    }
}
