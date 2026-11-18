package com.opentv.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 8.3.1 — Alarms do not survive a reboot: after the TV restarts,
 * re-arm the start alarms for every scheduled recording still in
 * the future, so a programme picked before the reboot still
 * records. Only re-arms alarms — it never starts the recording
 * service itself.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        RecordingManager.rearmScheduledRecordingAlarms(context)
    }
}
