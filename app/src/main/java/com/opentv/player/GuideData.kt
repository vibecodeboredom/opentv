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

    /** Build the row for one channel: its programmes inside the window,
     * or "No information" placeholders when it has none. Hidden
     * channels get no row (null). The window math lives here so the
     * search can build rows for channels outside the displayed set. */
    fun buildRow(
        channel: Channel,
        epg: Map<String, List<EpgParser.Programme>>,
        windowStartMillis: Long,
        windowHours: Int = 6,
        hidden: Set<String> = emptySet(),
        epgOverrides: Map<String, String> = emptyMap()
    ): GuideRow? {
        if (hidden.contains(channel.id)) return null
        return buildRowFromProgrammes(
            channel, programmesForChannel(channel, epg, epgOverrides),
            windowStartMillis, windowHours
        )
    }

    /** Same as buildRow, but the channel's programme list is already
     * fetched — the search calls this so it doesn't look the list up
     * twice (once for the title match, once for the row). */
    private fun buildRowFromProgrammes(
        channel: Channel,
        programmes: List<EpgParser.Programme>,
        windowStartMillis: Long,
        windowHours: Int = 6
    ): GuideRow? {
        val windowEnd = windowStartMillis + windowHours * 60L * 60 * 1000
        val inWindow = programmes
            .filter { it.stopMillis > windowStartMillis && it.startMillis < windowEnd }
            .sortedBy { it.startMillis }
        return if (inWindow.isNotEmpty()) {
            GuideRow(channel, inWindow)
        } else {
            GuideRow(
                channel,
                placeholderProgrammes(channel, windowStartMillis, windowEnd)
            )
        }
    }

    /** A channel's full programme list from the EPG map (no window
     * filter): tvg-id first (or its override), then the lowercased
     * display name — EPG sources often use different ids than the
     * playlist. Shared by buildRows and the search. */
    fun programmesForChannel(
        channel: Channel,
        epg: Map<String, List<EpgParser.Programme>>,
        epgOverrides: Map<String, String> = emptyMap()
    ): List<EpgParser.Programme> {
        val key = epgOverrides[channel.id]?.takeIf { it.isNotEmpty() }
            ?: channel.tvgId
        return key?.let { epg[it] }
            ?: epg[channel.name.lowercase()]
            ?: emptyList()
    }

    /** Split a search query into lowercase words. */
    fun searchWords(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }

    /** True when every word in [words] appears as a substring of [text]
     * (case-insensitive). Partial words match — typing "break" finds
     * "Breaking Bad" — and multi-word queries need every word present,
     * in any order. An empty word list never matches. */
    fun matchesWords(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        val lower = text.lowercase()
        return words.all { lower.contains(it) }
    }

    /**
     * Search the WHOLE playlist and the WHOLE EPG (4.9.0). The old
     * search only filtered the rows already on screen — capped at 400
     * channels and the current 6-hour window — so a channel past the
     * first 400, or a show airing later, was invisible even though it
     * was in the playlist. Now every channel is checked, and every
     * programme in its full EPG list (not just the visible window).
     *
     * Returns channel-name matches first (in playlist order), then
     * programme-title matches (in playlist order). Each result is a
     * normal guide row for the current window, so it displays in the
     * grid like any other row — including channels the guide would
     * never have built on its own.
     */
    fun searchRows(
        channels: List<Channel>,
        epg: Map<String, List<EpgParser.Programme>>,
        windowStartMillis: Long,
        windowHours: Int = 6,
        hidden: Set<String> = emptySet(),
        epgOverrides: Map<String, String> = emptyMap(),
        query: String
    ): List<GuideRow> {
        val words = searchWords(query)
        if (words.isEmpty()) return emptyList()
        val channelMatches = mutableListOf<GuideRow>()
        val programmeMatches = mutableListOf<GuideRow>()
        for (channel in channels) {
            if (hidden.contains(channel.id)) continue
            // Fetch the programme list once and use it for both the
            // title match and the row build — it used to be looked up
            // a second time inside buildRow.
            val programmes = programmesForChannel(channel, epg, epgOverrides)
            if (matchesWords(channel.name, words)) {
                buildRowFromProgrammes(channel, programmes, windowStartMillis, windowHours)
                    ?.let { channelMatches += it }
            } else if (programmes.any { matchesWords(it.title, words) }) {
                buildRowFromProgrammes(channel, programmes, windowStartMillis, windowHours)
                    ?.let { programmeMatches += it }
            }
        }
        return channelMatches + programmeMatches
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
        val rows = mutableListOf<GuideRow>()
        for (channel in channels) {
            if (rows.size >= MAX_GUIDE_ROWS) break
            buildRow(channel, epg, windowStartMillis, windowHours, hidden, epgOverrides)
                ?.let { rows += it }
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
        val cal = calendarFor(millis)
        val hour = cal.get(java.util.Calendar.HOUR)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val hour12 = if (hour == 0) 12 else hour
        return if (minute == 0) "$hour12 $amPm" else "$hour12:${minute.toString().padStart(2, '0')} $amPm"
    }

    /** Format a clock time as "8:00 PM" (always with minutes). */
    fun formatClock(millis: Long): String {
        val cal = calendarFor(millis)
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
        val cal = calendarFor(millis)
        return "${WEEKDAYS[cal.get(java.util.Calendar.DAY_OF_WEEK) - 1]}, " +
            "${MONTHS[cal.get(java.util.Calendar.MONTH)]} ${cal.get(java.util.Calendar.DAY_OF_MONTH)}"
    }

    private val WEEKDAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
    private val MONTHS = arrayOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
    )

    /**
     * One Calendar per thread, reused by the format helpers. They used
     * to allocate a fresh Calendar on every call — and they run on
     * every guide focus change (showDetails formats the time range
     * twice per move). Same fields, same output, no allocation.
     * ThreadLocal because the helpers are static and a shared instance
     * would not be safe if a background caller ever used one.
     */
    private val calendarThreadLocal = ThreadLocal<java.util.Calendar>()

    private fun calendarFor(millis: Long): java.util.Calendar {
        val cal = calendarThreadLocal.get()
            ?: java.util.Calendar.getInstance().also { calendarThreadLocal.set(it) }
        cal.timeInMillis = millis
        return cal
    }
}
