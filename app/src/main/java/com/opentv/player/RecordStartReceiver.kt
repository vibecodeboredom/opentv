package com.opentv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 8.3.0 — Fired by the AlarmManager when a scheduled recording's
 * programme starts: drops the schedule (it has fired), starts
 * recording the channel, and schedules the stop at the programme's
 * end. The alarm is inexact (setAndAllowWhileIdle, like the stop
 * alarm — no exact-alarm permission needed), so it may land a few
 * minutes late; a programme that already ended by the time the
 * alarm fires is skipped instead of recording dead air.
 */
class RecordStartReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_SCHEDULED_ID) ?: return
        val recording = PlaylistRepository.scheduledRecordings(context)
            .firstOrNull { it.id == id } ?: return
        PlaylistRepository.removeScheduledRecording(context, id)
        val now = System.currentTimeMillis()
        if (now >= recording.stopMillis) return
        val channel = Channel(
            id = recording.channelId,
            name = recording.channelName,
            url = recording.channelUrl,
            group = "",
            logo = null,
            tvgId = null
        )
        RecordingManager.startRecording(context, channel)
        RecordingManager.scheduleStopRecording(context, recording.channelId, recording.stopMillis)
        // The recording infra is awake anyway — enforce the
        // auto-delete setting while we are here.
        val keepDays = AppSettings.autoDeleteRecordingsDays(context)
        if (keepDays > 0) RecordingManager.pruneOldRecordings(context, keepDays, now)
    }

    companion object {
        const val EXTRA_SCHEDULED_ID = "scheduled_id"
    }
}
