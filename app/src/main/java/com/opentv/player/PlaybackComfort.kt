package com.opentv.player

/**
 * Combined port (from juniper-v10's 7.1.0 PlaybackComfort): the
 * pure logic behind playback speed and the recording seek skip —
 * no Android classes, so the option lists, labels and seek
 * clamping are unit-testable; PlaybackActivity owns the player
 * and the dialogs.
 *
 * Only the speed + seek parts came over: the sleep-timer half of
 * Juniper's file stayed behind because Nova's line already has
 * its own sleep timer (deadline-based, with end-of-programme and
 * the fade-out) — one timer, Nova's, wins.
 */
object PlaybackComfort {

    /** Playback speeds offered in the playback menu. */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    /** Menu label for a speed, e.g. 1.5f -> "1.5×", 1f -> "1×". */
    fun speedLabel(speed: Float): String {
        val text = if (speed == speed.toInt().toFloat()) speed.toInt().toString()
        else speed.toString()
        return "${text}×"
    }

    /** Recording seek skip: one Left/Right jump is this many ms. */
    const val SEEK_SKIP_MS = 10_000L

    /**
     * Clamp a recording seek target to [0, duration]. A duration of
     * 0 or less means unknown (no length reported yet) — then only
     * the lower bound applies.
     */
    fun clampSeek(positionMs: Long, durationMs: Long): Long {
        val lower = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0) lower.coerceAtMost(durationMs) else lower
    }

    /** A playback position as "m:ss" or "h:mm:ss" for the
     * recording seek toast. Negative values clamp to zero. */
    fun formatSeekTime(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0L) / 1000).toInt()
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) "$hours:${pad2(minutes)}:${pad2(seconds)}"
        else "$minutes:${pad2(seconds)}"
    }

    private fun pad2(value: Int): String = if (value < 10) "0$value" else "$value"
}
