package com.opentv.player

/**
 * Pure helpers for the TV guide (EPG grid). Kept free of Android classes so
 * the window math is unit-testable.
 */
object GuideData {

    /** One row of the guide: a channel and the programmes inside the window. */
    data class GuideRow(
        val channel: Channel,
        val programmes: List<EpgParser.Programme>
    )

    /** A programme cell's position in the grid, in minutes from the window start. */
    data class CellLayout(
        val programme: EpgParser.Programme,
        /** Minutes from the window start to the visible start of this cell. */
        val startOffsetMinutes: Long,
        /** Visible width of the cell, in minutes. */
        val widthMinutes: Long
    )

    /** Guide window: starts at the top of the current hour, spans [hours] hours. */
    fun windowStartMillis(nowMillis: Long): Long {
        val hour = 60L * 60 * 1000
        return (nowMillis / hour) * hour
    }

    /** Build the rows for the guide. Every visible channel gets a row: channels
     * with EPG data in the window show their real programmes; channels
     * without any EPG data get "No information" placeholders covering the
     * window, so the guide always renders (like TiviMate does). Hidden
     * channels are skipped; [epgOverrides] maps channel id -> tvg-id.
     * Capped at [MAX_GUIDE_ROWS] channels: the grid inflates every row
     * eagerly, so showing all 1500 playlist channels at once would be
     * too heavy. (A virtualized grid is the follow-up if he wants them all.) */
    fun buildRows(
        channels: List<Channel>,
        epg: Map<String, List<EpgParser.Programme>>,
        windowStartMillis: Long,
        windowHours: Int = 6,
        hidden: Set<String> = emptySet(),
        epgOverrides: Map<String, String> = emptyMap()
    ): List<GuideRow> {
        val windowEnd = windowStartMillis + windowHours * 60L * 60 * 1000
        val rows = mutableListOf<GuideRow>()
        for (channel in channels) {
            if (rows.size >= MAX_GUIDE_ROWS) break
            if (hidden.contains(channel.id)) continue
            val key = epgOverrides[channel.id]?.takeIf { it.isNotEmpty() }
                ?: channel.tvgId
            // Try the tvg-id first, then the channel name (lowercased) —
            // EPG sources often use different ids than the playlist.
            val programmes = (key?.let { epg[it] }
                ?: epg[channel.name.lowercase()]
                ?: emptyList())
                .filter { it.stopMillis > windowStartMillis && it.startMillis < windowEnd }
                .sortedBy { it.startMillis }
            if (programmes.isNotEmpty()) {
                rows += GuideRow(channel, programmes)
            } else {
                rows += GuideRow(
                    channel,
                    placeholderProgrammes(channel, windowStartMillis, windowEnd)
                )
            }
        }
        return rows
    }

    const val MAX_GUIDE_ROWS = 400

    /** "No information" placeholders covering the window in 1-hour blocks. */
    fun placeholderProgrammes(
        channel: Channel,
        windowStartMillis: Long,
        windowEndMillis: Long
    ): List<EpgParser.Programme> {
        val hour = 60L * 60 * 1000
        val list = mutableListOf<EpgParser.Programme>()
        var t = windowStartMillis
        while (t < windowEndMillis) {
            val end = minOf(t + hour, windowEndMillis)
            list += EpgParser.Programme(
                channelId = channel.tvgId ?: channel.id,
                startMillis = t,
                stopMillis = end,
                title = NO_INFORMATION
            )
            t = end
        }
        return list
    }

    const val NO_INFORMATION = "No information"

    /** Clamp a programme to the window and compute its cell position/width. */
    fun layoutCell(
        programme: EpgParser.Programme,
        windowStartMillis: Long,
        windowEndMillis: Long
    ): CellLayout {
        val visibleStart = maxOf(programme.startMillis, windowStartMillis)
        val visibleEnd = minOf(programme.stopMillis, windowEndMillis)
        val minute = 60_000L
        return CellLayout(
            programme = programme,
            startOffsetMinutes = (visibleStart - windowStartMillis) / minute,
            widthMinutes = maxOf((visibleEnd - visibleStart) / minute, 5L)
        )
    }

    /** Format a time as "8 PM" / "8:30 PM" for the guide header. */
    fun formatHourLabel(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val hour = cal.get(java.util.Calendar.HOUR)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val hour12 = if (hour == 0) 12 else hour
        return if (minute == 0) "$hour12 $amPm" else "$hour12:${minute.toString().padStart(2, '0')} $amPm"
    }

    /** Format a clock time as "8:00 PM" (always with minutes). */
    fun formatClock(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val hour = cal.get(java.util.Calendar.HOUR)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val hour12 = if (hour == 0) 12 else hour
        return "$hour12:${minute.toString().padStart(2, '0')} $amPm"
    }

    /** Format a programme's time span as "8:00 – 9:00 PM". */
    fun formatTimeRange(startMillis: Long, stopMillis: Long): String =
        "${formatClock(startMillis)} – ${formatClock(stopMillis)}"

    /** Fraction of a programme already aired (0.0–1.0) for the progress bar. */
    fun progressFraction(
        programme: EpgParser.Programme,
        nowMillis: Long
    ): Float {
        val total = (programme.stopMillis - programme.startMillis).coerceAtLeast(1L)
        return ((nowMillis - programme.startMillis).toFloat() / total).coerceIn(0f, 1f)
    }

    /** Whole minutes left in a programme ("48 min"). */
    fun remainingMinutes(programme: EpgParser.Programme, nowMillis: Long): Long =
        maxOf((programme.stopMillis - nowMillis) / 60_000L, 0L)

    /** Format date + clock as "Sat, Nov 7, 10:12 PM" for the guide header. */
    fun formatDateTime(millis: Long): String =
        "${formatDate(millis)}, ${formatClock(millis)}"

    /** Format the guide date as "Sat, Nov 7". */
    fun formatDate(millis: Long): String {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
        val days = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val months = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
        )
        return "${days[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]}, " +
            "${months[cal.get(java.util.Calendar.MONTH)]} ${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }
}
