package com.opentv.player

/**
 * 8.3.0 — Scheduled recordings: a future programme picked in the
 * guide, recorded when it starts. The schedule itself is just data
 * (channel + programme + start/stop) stored in SharedPreferences;
 * an AlarmManager alarm fires [RecordStartReceiver] at the start
 * time, which starts the recording and schedules its stop.
 *
 * The encode / decode is pure (newline-joined lines, "\u0001"
 * between fields — the same separator the group overrides use, so
 * a channel id can contain '=' from a URL query) so it is
 * unit-testable; only PlaylistRepository touches
 * SharedPreferences. Titles and names are cleaned of newlines/tabs
 * when stored, so a line always stays one entry.
 */
object ScheduledRecordings {

    /** One scheduled recording: what to record, and when. */
    data class ScheduledRecording(
        val channelId: String,
        val channelName: String,
        val channelUrl: String,
        val programmeTitle: String,
        val startMillis: Long,
        val stopMillis: Long
    ) {
        /** Stable id: channel + start time. Two programmes on one
         * channel never start at the same instant, so this is
         * unique per schedule — and it is also the alarm's
         * request-code source. */
        val id: String
            get() = "sched_" + channelId.replace(UNSAFE_CHARS, "_").take(60) +
                "_" + startMillis
    }

    private val UNSAFE_CHARS = Regex("[^A-Za-z0-9_.-]")
    private const val SEP = "\u0001"

    /** A field value safe to store: no newlines, tabs or the
     * separator, trimmed. */
    fun cleanField(value: String): String =
        value.replace("\n", " ").replace("\r", " ")
            .replace(SEP, " ").replace("\t", " ").trim()

    /** Encode a list as one recording per line, fields joined by
     * the separator. */
    fun encode(list: List<ScheduledRecording>): String =
        list.joinToString("\n") { rec ->
            listOf(
                cleanField(rec.channelId),
                cleanField(rec.channelName),
                cleanField(rec.channelUrl),
                cleanField(rec.programmeTitle),
                rec.startMillis.toString(),
                rec.stopMillis.toString()
            ).joinToString(SEP)
        }

    /** Decode the stored lines back into recordings. Lines with the
     * wrong field count, blank channel id / url, or unparseable
     * times are skipped — one bad line never breaks the rest. A
     * stop at or before its start is skipped too (a programme with
     * no duration cannot be recorded). */
    fun decode(raw: String): List<ScheduledRecording> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size != 6) return@mapNotNull null
            val channelId = parts[0]
            val channelUrl = parts[2]
            if (channelId.isBlank() || channelUrl.isBlank()) return@mapNotNull null
            val start = parts[4].toLongOrNull() ?: return@mapNotNull null
            val stop = parts[5].toLongOrNull() ?: return@mapNotNull null
            if (stop <= start) return@mapNotNull null
            ScheduledRecording(
                channelId = channelId,
                channelName = parts[1],
                channelUrl = channelUrl,
                programmeTitle = parts[3],
                startMillis = start,
                stopMillis = stop
            )
        }
    }

    /** The schedules still in the future, soonest first. Entries
     * whose programme already ended are dropped — a schedule that
     * never fired (the TV was off at its start time) must not sit
     * in the list forever. */
    fun upcoming(list: List<ScheduledRecording>, nowMillis: Long): List<ScheduledRecording> =
        list.filter { it.stopMillis > nowMillis }.sortedBy { it.startMillis }

    /** True when this channel + start time already has a schedule. */
    fun isScheduled(
        list: List<ScheduledRecording>,
        channelId: String,
        startMillis: Long
    ): Boolean = list.any { it.channelId == channelId && it.startMillis == startMillis }

    /** The schedule for this channel + start time, or null. */
    fun find(
        list: List<ScheduledRecording>,
        channelId: String,
        startMillis: Long
    ): ScheduledRecording? =
        list.firstOrNull { it.channelId == channelId && it.startMillis == startMillis }

    /** The list without the schedule with this id. */
    fun withoutId(list: List<ScheduledRecording>, id: String): List<ScheduledRecording> =
        list.filter { it.id != id }
}
