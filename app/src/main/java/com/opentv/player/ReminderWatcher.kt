package com.opentv.player

import android.app.Activity
import android.app.AlertDialog
import android.os.Handler
import android.os.Looper

/**
 * 8.0.0 (Nova): programme reminder prompts. A host activity starts
 * the watcher in onStart and stops it in onStop; while it runs, a
 * check every [CHECK_MS] looks for a stored reminder whose start
 * time has arrived and shows the "starting now" prompt.
 *
 * The reminder is removed BEFORE the prompt shows, so it can never
 * fire twice — not from a second host, not from the auto-dismiss
 * racing a key press. The prompt is an ordinary AlertDialog: Watch
 * now runs the host's callback, Dismiss / BACK / any other key
 * closes it, and it dismisses itself after [AUTO_DISMISS_MS] so a
 * prompt nobody is watching never sits on screen.
 *
 * In-app only by design: reminders live in SharedPreferences and
 * prompt only while the app is in the foreground — there are no
 * system notifications (a TV app that may be closed for days gains
 * nothing from a notification shade nobody opens). Reminders whose
 * programme ended while the app was closed are pruned on read (see
 * PlaylistRepository.reminders) instead of prompting late.
 */
class ReminderWatcher(
    private val activity: Activity,
    private val onWatchNow: (PlaylistRepository.Reminder) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var dialog: AlertDialog? = null

    private val checkRunnable = object : Runnable {
        override fun run() {
            check()
            handler.postDelayed(this, CHECK_MS)
        }
    }

    private val autoDismissRunnable = Runnable {
        if (dialog?.isShowing == true) dialog?.dismiss()
    }

    fun start() {
        handler.removeCallbacks(checkRunnable)
        handler.postDelayed(checkRunnable, CHECK_MS)
    }

    fun stop() {
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(autoDismissRunnable)
        if (dialog?.isShowing == true) dialog?.dismiss()
        dialog = null
    }

    private fun check() {
        if (activity.isFinishing || activity.isDestroyed) return
        if (dialog?.isShowing == true) return
        val now = System.currentTimeMillis()
        val due = PlaylistRepository.reminders(activity)
            .firstOrNull { it.startMillis <= now } ?: return
        // Retire it first: whatever happens to the prompt, this
        // reminder has fired and must not fire again.
        PlaylistRepository.removeReminder(activity, due.channelId, due.startMillis)
        dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.reminder_prompt_title)
            .setMessage(
                activity.getString(
                    R.string.reminder_prompt_message, due.title, due.channelName
                )
            )
            .setPositiveButton(R.string.reminder_watch_now) { _, _ ->
                onWatchNow(due)
            }
            .setNegativeButton(R.string.reminder_dismiss, null)
            .setOnDismissListener {
                dialog = null
                handler.removeCallbacks(autoDismissRunnable)
            }
            .show()
        handler.removeCallbacks(autoDismissRunnable)
        handler.postDelayed(autoDismissRunnable, AUTO_DISMISS_MS)
    }

    private companion object {
        /** How often due reminders are checked. A prompt can land
         * up to this long after the programme's listed start —
         * close enough for a "starting now" nudge, and a 15-second
         * prefs read is nothing next to the guide's own tickers. */
        const val CHECK_MS = 15_000L
        /** A prompt nobody answers dismisses itself after this. */
        const val AUTO_DISMISS_MS = 60_000L
    }
}
