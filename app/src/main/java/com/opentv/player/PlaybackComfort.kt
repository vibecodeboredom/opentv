package com.opentv.player

/**
 * 7.1.0 — Playback comfort: sleep timer, playback speed, recording
 * seek skip. Pure logic — no Android classes — so the option lists,
 * labels and seek clamping are unit-testable; PlaybackActivity owns
 * the player, the timer handler and the dialogs.
 * 7.1.1 — seek-time formatting, so a recording seek says where
 * it landed.
 */
object PlaybackComfort {

    /** Sleep-timer choices offered in the playback menu (minutes). */
    val SLEEP_MINUTES = listOf(15, 30, 60, 90)

    /** Delay in ms for a sleep timer of [minutes] minutes. */
    fun sleepDelayMillis(minutes: Int): Long {
        require(minutes > 0) { "sleep timer minutes must be positive, was $minutes" }
        return minutes * 60_000L
    }

    /** Menu label for the sleep timer: "Off" when null, else "30 min". */
    fun sleepLabel(minutes: Int?): String =
        minutes?.let { "$it min" } ?: "Off"

    /** 7.1.1: a playback position as "m:ss" or "h:mm:ss" for the
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
}
