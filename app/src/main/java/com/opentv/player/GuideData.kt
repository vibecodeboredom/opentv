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
            } else if (programmes.any {
                    // 9.3.0 (Nova): the description matches too —
                    // a word that only appears in a programme's
                    // synopsis ("documentary about the 1969 moon
                    // landing") now finds the programme, ranked
                    // with the title matches.
                    matchesWords(it.title, words) ||
                        (it.desc.isNotEmpty() && matchesWords(it.desc, words))
                }
            ) {
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

    /** 8.1.0 (Nova): the playlist's distinct channel groups, in
     * playlist order (first appearance wins) — the guide group
     * filter's choices. Groups are never empty strings: the parser
     * defaults a missing group-title to "Other". */
    fun channelGroups(channels: List<Channel>): List<String> {
        val seen = LinkedHashSet<String>()
        for (channel in channels) seen += channel.group
        return seen.toList()
    }

    /** 8.1.0 (Nova): the group filter that actually applies — the
     * stored choice when some channel in the playlist still
     * carries that group, null (all groups) when nothing is stored
     * or the group has left the playlist, so a stale preference
     * can never filter the guide down to nothing on its own. */
    fun effectiveGroupFilter(channels: List<Channel>, stored: String?): String? =
        stored?.takeIf { group -> channels.any { it.group == group } }

    /** 8.1.0 (Nova): restrict a channel list to one group, playlist
     * order kept; a null group returns the list untouched. The
     * guide applies this to the channel list BEFORE rowChannels /
     * LazyRows, so filtering happens at the row source and the
     * lazy row machinery never sees the filtered-out channels. */
    fun filterChannelsByGroup(channels: List<Channel>, group: String?): List<Channel> =
        if (group == null) channels else channels.filter { it.group == group }

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

    /** 9.0.0 (Nova): format a playback position / duration as an
     * elapsed clock — "12:34", or "1:02:34" once past an hour.
     * Used by recording resume (the "Resumed from" toast and the
     * Continue watching cards). */
    fun formatElapsedClock(millis: Long): String {
        val totalSeconds = (millis / 1000).coerceAtLeast(0)
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            "$hours:${twoDigits(minutes.toInt())}:${twoDigits(seconds.toInt())}"
        } else {
            "${minutes}:${twoDigits(seconds.toInt())}"
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

    /** 9.3.0 (Nova): a span until something starts, compactly —
     * "45m" under an hour, "2h 15m" under a day (whole hours drop
     * the minutes: "2h"), "3d 4h" beyond (whole days: "3d"). The
     * watchlist cards prefix it with "in". A span of a minute or
     * less still reads "1m" — the countdown never claims "0m"
     * for something that hasn't started yet; a non-positive span
     * (already started) reads "0m" and callers show "On now"
     * instead. */
    fun formatCountdown(deltaMillis: Long): String {
        if (deltaMillis <= 0L) return "0m"
        val minutes = maxOf(deltaMillis / 60_000L, 1L)
        if (minutes < 60L) return "${minutes}m"
        val hours = minutes / 60L
        val remMinutes = minutes % 60L
        if (hours < 24L) {
            return if (remMinutes > 0L) "${hours}h ${remMinutes}m" else "${hours}h"
        }
        val days = hours / 24L
        val remHours = hours % 24L
        return if (remHours > 0L) "${days}d ${remHours}h" else "${days}d"
    }

    /** 9.3.1 (Nova): a short window of a programme description
     * around the first occurrence of [keywordLower] (already
     * lowercase), so the keyword hits list can show why a
     * description-only hit matched. Whitespace is collapsed to
     * single spaces; "…" marks a cut at either end. When the
     * keyword isn't found (callers check containment first, so
     * this is only a fallback) the head of the description is
     * returned. */
    fun descSnippet(desc: String, keywordLower: String): String {
        val flat = desc.replace(Regex("\\s+"), " ").trim()
        val idx = flat.lowercase().indexOf(keywordLower)
        if (idx < 0) {
            return if (flat.length > SNIPPET_WINDOW) {
                flat.substring(0, SNIPPET_WINDOW) + "…"
            } else {
                flat
            }
        }
        val start = maxOf(0, idx - SNIPPET_LEAD)
        val end = minOf(flat.length, idx + keywordLower.length + SNIPPET_TAIL)
        val window = flat.substring(start, end)
        return (if (start > 0) "…" else "") + window +
            (if (end < flat.length) "…" else "")
    }

    /** 9.3.1 (Nova): descSnippet window sizes — a little context
     * before the keyword, more after it (that's where the
     * sentence usually goes). */
    private const val SNIPPET_LEAD = 20
    private const val SNIPPET_TAIL = 40
    private const val SNIPPET_WINDOW = 60

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
