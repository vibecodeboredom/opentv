package com.opentv.player

/**
 * Minimal M3U playlist parser.
 *
 * Understands the #EXTINF lines used by iptv-org and most providers:
 *   #EXTINF:-1 tvg-id="CNN.us" tvg-logo="https://..." group-title="News",CNN
 *   https://example.com/cnn.m3u8
 */
object M3uParser {

    private val attrRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val channels = mutableListOf<Channel>()
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.startsWith("#EXTINF")) {
                val attrs = attrRegex.findAll(line).associate { it.groupValues[1] to it.groupValues[2] }
                val name = line.substringAfter(",", "").trim().ifEmpty { attrs["tvg-name"] ?: "Unknown" }
                val url = lines.getOrNull(i + 1)?.takeIf { !it.startsWith("#") }?.trim().orEmpty()
                if (url.isNotEmpty()) {
                    val tvgId = attrs["tvg-id"]
                    val id = tvgId?.takeIf { it.isNotEmpty() } ?: "$name|$url"
                    channels += Channel(
                        id = id,
                        name = name,
                        url = url,
                        group = attrs["group-title"]?.takeIf { it.isNotEmpty() } ?: "Other",
                        logo = attrs["tvg-logo"]?.takeIf { it.isNotEmpty() },
                        tvgId = tvgId,
                        number = channels.size + 1
                    )
                    i += 2
                    continue
                }
            }
            i++
        }
        return channels
    }
}
