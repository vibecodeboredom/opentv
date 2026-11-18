package com.opentv.player

/**
 * 8.2.0 — EPG quality: per-source fetch status, channels without
 * guide data (+ one-tap EPG id suggestions for the common id
 * mismatches), and per-channel guide coverage. All pure (no
 * Context, no Android framework) so it is unit-testable on the JVM.
 */
object EpgQuality {

    // ---- Per-source status -------------------------------------------

    /** One EPG source's last fetch outcome, as recorded by
     * loadEpgFresh. Persisted in SharedPreferences (see
     * PlaylistRepository.epgSourceStatuses) so Settings can show it
     * even when the guide has not loaded this session. */
    data class SourceStatus(
        val url: String,
        val lastFetchedAtMillis: Long,
        val lastOk: Boolean,
        val lastError: String?,
        val channelCount: Int,
        val programmeCount: Int
    )

    /** Encode statuses as one prefs string: one tab-separated line
     * per source. Tabs/newlines never appear in a URL, and the error
     * is flattened to a single short line on encode. */
    fun encodeStatuses(statuses: List<SourceStatus>): String =
        statuses.joinToString("\n") { s ->
            listOf(
                s.url,
                s.lastFetchedAtMillis.toString(),
                if (s.lastOk) "1" else "0",
                s.channelCount.toString(),
                s.programmeCount.toString(),
                (s.lastError ?: "")
                    .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                    .take(80)
            ).joinToString("\t")
        }

    /** Decode the prefs string. Malformed lines are skipped, so a
     * half-written value never kills the whole list. */
    fun decodeStatuses(raw: String): List<SourceStatus> {
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("\t")
            if (parts.size < 5) return@mapNotNull null
            val url = parts[0]
            if (url.isBlank()) return@mapNotNull null
            SourceStatus(
                url = url,
                lastFetchedAtMillis = parts[1].toLongOrNull() ?: 0L,
                lastOk = parts[2] == "1",
                lastError = parts.getOrNull(5)?.takeIf { it.isNotEmpty() },
                channelCount = parts[3].toIntOrNull() ?: 0,
                programmeCount = parts[4].toIntOrNull() ?: 0
            )
        }
    }

    /** One line for a source row in Settings. */
    fun statusLine(status: SourceStatus?, nowMillis: Long): String = when {
        status == null || status.lastFetchedAtMillis <= 0L -> "Not fetched yet"
        status.lastOk ->
            "Loaded ${GuideData.formatAge(status.lastFetchedAtMillis, nowMillis)}" +
                " · ${status.channelCount} channels, ${status.programmeCount} programmes"
        else ->
            "Failed ${GuideData.formatAge(status.lastFetchedAtMillis, nowMillis)}" +
                (status.lastError?.let { " — $it" } ?: "")
    }

    // ---- Channels without guide data ---------------------------------

    /** Channels with no programmes at all in the merged EPG map
     * (override / tvg-id / lowercased name all miss). Hidden
     * channels are skipped — they are not in the guide anyway. */
    fun channelsWithoutGuide(
        channels: List<Channel>,
        epg: Map<String, List<EpgParser.Programme>>,
        epgOverrides: Map<String, String> = emptyMap(),
        hidden: Set<String> = emptySet()
    ): List<Channel> =
        channels.filter { it.id !in hidden &&
            GuideData.programmesForChannel(it, epg, epgOverrides).isEmpty() }

    /** Normalize an EPG id or channel name for matching: lowercase,
     * only letters and digits kept. "CNN.us", "cnn_us" and "CNN US"
     * all normalize to "cnnus". */
    fun normalizeId(value: String): String =
        value.lowercase().filter { it.isLetterOrDigit() }

    /** Common id suffixes that differ between playlist and guide:
     * country codes and HD markers. */
    private val ID_SUFFIXES =
        listOf("us", "uk", "eu", "de", "fr", "es", "it", "nl", "ca", "au", "mx", "br", "hd")

    /** Drop a trailing ".us" / "-hd" / "_uk" style suffix from a
     * lowercased id, so "cnn.us" compares as "cnn". The separator is
     * required — "funk" must not lose its "uk". */
    private fun stripIdSuffix(lowercased: String): String {
        for (suffix in ID_SUFFIXES) {
            for (sep in listOf('.', '-', '_')) {
                val tail = "$sep$suffix"
                if (lowercased.endsWith(tail) && lowercased.length > tail.length) {
                    return lowercased.dropLast(tail.length)
                }
            }
        }
        return lowercased
    }

    /** The canonical form of an id or name for matching: suffix
     * stripped, then normalized. */
    private fun matchKey(value: String): String =
        normalizeId(stripIdSuffix(value.lowercase()))

    /** Ranked EPG-id suggestions for a channel with no guide data,
     * from the merged EPG map's keys (tvg-ids + lowercased display
     * names). Most common mismatches first:
     *  1. the tvg-id itself, differing only in case/punctuation
     *     ("CNN.us" vs "cnn_us");
     *  2. the tvg-id with a country/HD suffix added or removed
     *     ("cnn" vs "cnn.us");
     *  3. the channel name matching an EPG display name
     *     ("CNN International" vs "cnn-international");
     *  4. the channel name contained in an EPG id, or vice versa
     *     ("cnn" vs "cnn international").
     * Capped at [max]. An override set to any returned id resolves
     * in programmesForChannel, since it looks the value up directly.
     */
    fun suggestEpgIds(
        channel: Channel,
        epgKeys: Set<String>,
        max: Int = 5
    ): List<String> {
        if (epgKeys.isEmpty()) return emptyList()
        val found = linkedSetOf<String>()
        val tvg = channel.tvgId?.takeIf { it.isNotBlank() }
        val normTvg = tvg?.let { normalizeId(it) }
        val keyTvg = tvg?.let { matchKey(it) }
        val normName = normalizeId(channel.name)
        val keyName = matchKey(channel.name)
        // 1: tvg-id, case/punctuation only.
        if (normTvg != null) {
            for (key in epgKeys) {
                if (normalizeId(key) == normTvg) found += key
            }
        }
        // 2: tvg-id, suffix added or removed.
        if (keyTvg != null) {
            for (key in epgKeys) {
                if (matchKey(key) == keyTvg) found += key
            }
        }
        // 3: channel name matching an EPG display name.
        for (key in epgKeys) {
            if (normalizeId(key) == normName || matchKey(key) == keyName) found += key
        }
        // 4: containment, either way round (short side at least 3
        // characters, so "tv" does not match everything).
        for (key in epgKeys) {
            val k = matchKey(key)
            if (k.length >= 3 && keyName.length >= 3 &&
                (k.startsWith(keyName) || keyName.startsWith(k))
            ) found += key
        }
        return found.take(max)
    }

    // ---- Per-channel coverage ----------------------------------------

    /** Guide coverage for one channel: how many programmes it has
     * in the next [windowHours] hours, whether one is on now, and
     * when the next one starts (null when there is none). */
    data class Coverage(
        val programmeCount: Int,
        val hasNow: Boolean,
        val nextStartMillis: Long?
    )

    fun coverageFor(
        channel: Channel,
        epg: Map<String, List<EpgParser.Programme>>,
        epgOverrides: Map<String, String> = emptyMap(),
        nowMillis: Long = System.currentTimeMillis(),
        windowHours: Int = 24
    ): Coverage {
        val list = GuideData.programmesForChannel(channel, epg, epgOverrides)
        if (list.isEmpty()) return Coverage(0, false, null)
        val windowEnd = nowMillis + windowHours * 3_600_000L
        var count = 0
        var hasNow = false
        var nextStart: Long? = null
        for (p in list) {
            if (p.stopMillis <= nowMillis) continue
            if (p.startMillis >= windowEnd) continue
            count++
            if (p.startMillis <= nowMillis) hasNow = true
            else if (nextStart == null || p.startMillis < nextStart) nextStart = p.startMillis
        }
        return Coverage(count, hasNow, nextStart)
    }

    /** One line for the guide's channel options. */
    fun coverageLine(
        coverage: Coverage,
        nowMillis: Long,
        windowHours: Int = 24
    ): String = when {
        coverage.programmeCount == 0 -> "Guide: no data for this channel"
        coverage.hasNow ->
            "Guide: ${coverage.programmeCount} programmes in the next ${windowHours}h · on now"
        coverage.nextStartMillis != null ->
            "Guide: ${coverage.programmeCount} programmes in the next ${windowHours}h" +
                " · next ${GuideData.formatClock(coverage.nextStartMillis)}"
        else ->
            "Guide: ${coverage.programmeCount} programmes in the next ${windowHours}h"
    }
}
