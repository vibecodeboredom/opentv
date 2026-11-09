package com.opentv.player

/**
 * Minimal M3U playlist parser.
 *
 * Understands the #EXTINF lines used by iptv-org and most providers:
 *   #EXTINF:-1 tvg-id="CNN.us" tvg-logo="https://..." group-title="News",CNN
 *   https://example.com/cnn.m3u8
 *
 * Written for speed: the playlist has thousands of channels, and the old
 * version ran a regex over every #EXTINF line and built a Map of all its
 * attributes just to read four of them. This version scans for only the
 * attributes it needs (tvg-id, tvg-logo, group-title, tvg-name, plus the
 * 5.0.0 catch-up attributes) with plain indexOf/substring, and iterates
 * the lines lazily instead of materializing the whole file into a list
 * first.
 *
 * 5.0.0 — catch-up: a channel may declare catchup / catchup-days /
 * catchup-source on its #EXTINF line, or the playlist may declare them
 * once on the #EXTM3U header line as playlist-wide defaults. A
 * channel's own values win over the header ones.
 */
object M3uParser {

    fun parse(text: String): List<Channel> {
        val channels = ArrayList<Channel>(2048)
        var pendingExtinf: String? = null
        // Playlist-wide catch-up defaults from the #EXTM3U header.
        var headerCatchupType: String? = null
        var headerCatchupDays: Int? = null
        var headerCatchupSource: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTM3U")) {
                headerCatchupType = attr(line, KEY_CATCHUP)
                headerCatchupDays = attr(line, KEY_CATCHUP_DAYS)?.toIntOrNull()
                headerCatchupSource = attr(line, KEY_CATCHUP_SOURCE)
                    ?.takeIf { it.isNotEmpty() }
                continue
            }
            if (line.startsWith("#EXTINF")) {
                pendingExtinf = line
                continue
            }
            val extinf = pendingExtinf
            pendingExtinf = null
            if (extinf == null) continue
            if (line.startsWith("#")) continue
            val url = line
            val name = extinf.substringAfter(",", "").trim()
                .ifEmpty { attr(extinf, KEY_TVG_NAME) ?: "Unknown" }
            val tvgId = attr(extinf, KEY_TVG_ID)
            val id = tvgId?.takeIf { it.isNotEmpty() } ?: "$name|$url"
            channels += Channel(
                id = id,
                name = name,
                url = url,
                group = attr(extinf, KEY_GROUP_TITLE)?.takeIf { it.isNotEmpty() } ?: "Other",
                logo = attr(extinf, KEY_TVG_LOGO)?.takeIf { it.isNotEmpty() },
                tvgId = tvgId,
                number = channels.size + 1,
                catchupType = attr(extinf, KEY_CATCHUP) ?: headerCatchupType,
                catchupDays = attr(extinf, KEY_CATCHUP_DAYS)?.toIntOrNull()
                    ?: headerCatchupDays,
                catchupSource = attr(extinf, KEY_CATCHUP_SOURCE)
                    ?.takeIf { it.isNotEmpty() } ?: headerCatchupSource
            )
        }
        return channels
    }

    /** The full `key="` search tokens, as constants — attr() used to
     * rebuild one (a string-template allocation) for every attribute
     * of every channel, ~10k throwaway strings per playlist parse. */
    private const val KEY_TVG_ID = "tvg-id=\""
    private const val KEY_TVG_NAME = "tvg-name=\""
    private const val KEY_TVG_LOGO = "tvg-logo=\""
    private const val KEY_GROUP_TITLE = "group-title=\""
    private const val KEY_CATCHUP = "catchup=\""
    private const val KEY_CATCHUP_DAYS = "catchup-days=\""
    private const val KEY_CATCHUP_SOURCE = "catchup-source=\""

    /** Value of key="..." in an #EXTINF line, or null when absent.
     * [token] is the full `key="` prefix (one of the constants above). */
    private fun attr(line: String, token: String): String? {
        val start = line.indexOf(token)
        if (start < 0) return null
        val valueStart = start + token.length
        val end = line.indexOf('"', valueStart)
        if (end < 0) return null
        return line.substring(valueStart, end)
    }
}
