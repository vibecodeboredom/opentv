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
        // 8.3.1: the recording service may legitimately not be
        // running any more (the recording was stopped by hand, or
        // the start never ran) — stopping then tries to start a
        // foreground service from the background, which Android 12+
        // refuses. There is nothing to stop in that case, so swallow
        // it instead of crashing the receiver.
        runCatching { RecordingManager.stopRecording(context, channelId) }
    }

    companion object {
        const val EXTRA_CHANNEL_ID = "channel_id"
    }
}
