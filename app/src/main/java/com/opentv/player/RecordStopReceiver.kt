package com.opentv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Fired by the AlarmManager for a custom recording: stops the recording
 * for the channel named in [EXTRA_CHANNEL_ID].
 */
class RecordStopReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val channelId = intent.getStringExtra(EXTRA_CHANNEL_ID) ?: return
        RecordingManager.stopRecording(context, channelId)
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }
}
