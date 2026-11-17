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

    /** 7.2.0: the guide window's anchor — the top of the current
     * hour, shifted by whole days for day navigation (0 = today,
     * +1 = tomorrow). The window is [pastHours] back from the
     * anchor and the forward hours ahead of it. */
    fun dayAnchorMillis(nowMillis: Long, dayOffset: Int): Long {
        val day = 24L * 60 * 60 * 1000
        return windowStartMillis(nowMillis) + dayOffset * day
    }

    /** 7.2.0: tonight's prime-time window, 6–11 PM local time.
     * After 11 PM tonight is over, so it means tomorrow 6–11 PM.
     * Returns (window start, window hours). */
    fun tonightWindow(nowMillis: Long): Pair<Long, Int> {
        val hour = calendarFor(nowMillis).get(java.util.Calendar.HOUR_OF_DAY)
        var dayStart = midnightMillis(nowMillis)
        if (hour >= TONIGHT_END_HOUR) dayStart += 24L * 60 * 60 * 1000
        return Pair(dayStart + TONIGHT_START_HOUR * 60L * 60 * 1000, TONIGHT_HOURS)
    }

    /** Midnight (00:00) of the day containing [millis], local time. */
    private fun midnightMillis(millis: Long): Long {
        val cal = calendarFor(millis)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    /** Tonight's prime-time hours (7.2.0). */
    const val TONIGHT_START_HOUR = 18
    const val TONIGHT_END_HOUR = 23
    const val TONIGHT_HOURS = 5

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
        // One list, sorted in place — the old filter().sortedBy()
        // built two throwaway lists per row (400 rows per guide build).
        val inWindow = programmes.filterTo(mutableListOf()) {
            it.stopMillis > windowStartMillis && it.startMillis < windowEnd
        }
        inWindow.sortBy { it.startMillis }
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

    /** Split a search query into lowercase words. The whitespace
     * regex is compiled once — it used to be rebuilt on every call —
     * and the trim is folded into the empty-word filter, so no
     * intermediate trimmed copy is made. */
    fun searchWords(query: String): List<String> =
        query.lowercase().split(WHITESPACE_REGEX).filter { it.isNotEmpty() }

    /** True when every word in [words] appears as a substring of [text]
     * (case-insensitive). Partial words match — typing "break" finds
     * "Breaking Bad" — and multi-word queries need every word present,
     * in any order. An empty word list never matches. The case-insensitive
     * contains() avoids lowercasing the whole text on every check — a
     * search used to allocate a lowercase copy of every programme title
     * in the EPG (tens of thousands) per keystroke. */
    fun matchesWords(text: String, words: List<String>): Boolean {
        if (words.isEmpty()) return false
        return words.all { text.contains(it, ignoreCase = true) }
    }

    private val WHITESPACE_REGEX = Regex("\\s+")

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
        for (channel in rowChannels(channels, hidden)) {
            buildRow(channel, epg, windowStartMillis, windowHours, hidden, epgOverrides)
                ?.let { rows += it }
        }
        return rows
    }

    /** The channels that get a guide row, in order: the non-hidden
     * channels, capped at [MAX_GUIDE_ROWS]. This is exactly the set
     * buildRows produces rows for (buildRow returns null only for
     * hidden channels), split out so the guide screen can compute
     * the cheap part — a filter over the channel list — up front and
     * build the rows themselves lazily via [LazyRows]. */
    fun rowChannels(
        channels: List<Channel>,
        hidden: Set<String> = emptySet()
    ): List<Channel> =
        channels.filter { !hidden.contains(it.id) }.take(MAX_GUIDE_ROWS)

    /**
     * Guide rows built one at a time, on first access, instead of all
     * at once: the guide screen only ever displays the first rows on
     * open and appends more as the user scrolls, so computing every
     * row's programme filter + sort up front (up to [MAX_GUIDE_ROWS]
     * rows) was work the open path paid for rows nobody had looked
     * at yet. A row is computed by the same buildRow the eager
     * buildRows uses and then cached, so the list's contents are
     * identical to buildRows' — only the timing differs.
     *
     * [channels] must already be the row-producing set (non-hidden,
     * capped — see [rowChannels]); buildRow only returns null for a
     * hidden channel, which by construction cannot be in the list.
     * Access follows the same main-thread pattern as the guide's
     * other row lists (the screen builds and reads rows on the main
     * thread); the cache is a plain array, not synchronised.
     */
    class LazyRows(
        /** The row-producing channels, in row order. Exposed so a
         * caller can compare or search the channel sequence without
         * materialising any rows (the guide's EPG fill-in and its
         * open-on-current-channel lookup both do this). */
        val channels: List<Channel>,
        private val epg: Map<String, List<EpgParser.Programme>>,
        private val windowStartMillis: Long,
        private val windowHours: Int = 6,
        private val epgOverrides: Map<String, String> = emptyMap()
    ) : AbstractList<GuideRow>() {

        private val built = arrayOfNulls<GuideRow>(channels.size)

        override val size: Int get() = channels.size

        override fun get(index: Int): GuideRow {
            if (index !in channels.indices) {
                throw IndexOutOfBoundsException("index $index, size ${channels.size}")
            }
            built[index]?.let { return it }
            val row = requireNotNull(
                buildRow(
                    channels[index], epg, windowStartMillis,
                    windowHours, emptySet(), epgOverrides
                )
            ) { "LazyRows channel was not hidden-filtered: ${channels[index].id}" }
            built[index] = row
            return row
        }

        /** Index of a channel's row, or -1 — reads only the channel
         * list, so finding a row never builds the rows before it. */
        fun indexOfChannel(channelId: String?): Int =
            channels.indexOfFirst { it.id == channelId }
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
        return if (minute == 0) "$hour12 $amPm" else "$hour12:${twoDigits(minute)} $amPm"
    }

    /** Format a clock time as "8:00 PM" (always with minutes). */
    fun formatClock(millis: Long): String {
        val cal = calendarFor(millis)
        val hour = cal.get(java.util.Calendar.HOUR)
        val minute = cal.get(java.util.Calendar.MINUTE)
        val amPm = if (cal.get(java.util.Calendar.AM_PM) == java.util.Calendar.AM) "AM" else "PM"
        val hour12 = if (hour == 0) 12 else hour
        return "$hour12:${twoDigits(minute)} $amPm"
    }

    /** Two-digit zero-padded number ("05", "30") without padStart /
     * String.format — the format helpers run on every guide focus
     * change, so the padding is a plain branch. */
    private fun twoDigits(v: Int): String = if (v < 10) "0$v" else v.toString()

    /** Format a programme's time span as "8:00 – 9:00 PM". */
    fun formatTimeRange(startMillis: Long, stopMillis: Long): String =
        "${formatClock(startMillis)} – ${formatClock(stopMillis)}"

    /** First channel with this playlist number, or null. Numbers are
     * the playlist positions (1-based); a playlist can carry gaps, so
     * the first match wins. 7.0.0: channel-number jump. */
    fun channelByNumber(
        channels: List<Channel>,
        number: Int
    ): Channel? = channels.firstOrNull { it.number == number }

    /** How long ago [updatedAtMillis] was, as "just now", "5 min ago",
     * "2 h ago" or "3 d ago". 7.0.0: the guide header's EPG freshness
     * label. A zero or future timestamp reads as "just now". */
    fun formatAge(updatedAtMillis: Long, nowMillis: Long): String {
        val ageMinutes = (nowMillis - updatedAtMillis) / 60_000L
        return when {
            ageMinutes < 1 -> "just now"
            ageMinutes < 60 -> "$ageMinutes min ago"
            ageMinutes < 60 * 24 -> "${ageMinutes / 60} h ago"
            else -> "${ageMinutes / (60 * 24)} d ago"
        }
    }

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
