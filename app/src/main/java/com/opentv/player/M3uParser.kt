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
 * four attributes it needs (tvg-id, tvg-logo, group-title, tvg-name)
 * with plain indexOf/substring, and iterates the lines lazily instead of
 * materializing the whole file into a list first.
 */
object M3uParser {

    fun parse(text: String): List<Channel> {
        val channels = ArrayList<Channel>(2048)
        var pendingExtinf: String? = null
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
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
                .ifEmpty { attr(extinf, "tvg-name") ?: "Unknown" }
            val tvgId = attr(extinf, "tvg-id")
            val id = tvgId?.takeIf { it.isNotEmpty() } ?: "$name|$url"
            channels += Channel(
                id = id,
                name = name,
                url = url,
                group = attr(extinf, "group-title")?.takeIf { it.isNotEmpty() } ?: "Other",
                logo = attr(extinf, "tvg-logo")?.takeIf { it.isNotEmpty() },
                tvgId = tvgId,
                number = channels.size + 1
            )
        }
        return channels
    }

    /** Value of key="..." in an #EXTINF line, or null when absent. */
    private fun attr(line: String, key: String): String? {
        val start = line.indexOf("$key=\"")
        if (start < 0) return null
        val valueStart = start + key.length + 2
        val end = line.indexOf('"', valueStart)
        if (end < 0) return null
        return line.substring(valueStart, end)
    }
}
