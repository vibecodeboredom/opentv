package com.opentv.player

import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal XMLTV parser. Only keeps programme titles with start/stop times,
 * keyed by channel id, so the player can show "Now" / "Next".
 *
 * Uses the JDK DOM parser (javax.xml) so it works both on-device and in
 * plain JVM unit tests (Android's XmlPullParser is a stub there).
 */
object EpgParser {

    data class Programme(
        val channelId: String,
        val startMillis: Long,
        val stopMillis: Long,
        val title: String
    )

    private val xmltvDate = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Single-pass parse: returns the programmes map AND the channel
     * id -> display-name map from one DOM parse. Parsing the XML twice
     * (once for programmes, once for names) was doubling the guide's
     * load time, so callers that need both should use this.
     */
    fun parseWithNames(xml: String): Pair<Map<String, List<Programme>>, Map<String, String>> {
        val programmes = mutableMapOf<String, MutableList<Programme>>()
        val names = mutableMapOf<String, String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            // 6.1.0: parse from a StringReader instead of copying the
            // whole multi-MB XML string into a byte array first — the
            // string, the byte copy, and the DOM tree were all live
            // at once on a 1GB box.
            val doc = builder.parse(InputSource(StringReader(xml)))
            val programmeNodes = doc.getElementsByTagName("programme")
            for (i in 0 until programmeNodes.length) {
                val el = programmeNodes.item(i) as? Element ?: continue
                val channel = el.getAttribute("channel")
                val start = parseDate(el.getAttribute("start"))
                val stop = parseDate(el.getAttribute("stop"))
                val title = el.getElementsByTagName("title").item(0)?.textContent?.trim()
                if (channel.isNotEmpty() && !title.isNullOrEmpty() && stop > start) {
                    programmes.getOrPut(channel) { mutableListOf() } += Programme(
                        channelId = channel,
                        startMillis = start,
                        stopMillis = stop,
                        title = title
                    )
                }
            }
            val channelNodes = doc.getElementsByTagName("channel")
            for (i in 0 until channelNodes.length) {
                val el = channelNodes.item(i) as? Element ?: continue
                val id = el.getAttribute("id")
                val name = el.getElementsByTagName("display-name").item(0)
                    ?.textContent?.trim()
                if (id.isNotEmpty() && !name.isNullOrEmpty()) {
                    names[id] = name
                }
            }
        } catch (ignored: Exception) {
            // EPG is best-effort; a bad file just means no guide data.
        }
        return programmes to names
    }

    fun parse(xml: String): Map<String, List<Programme>> = parseWithNames(xml).first

    /** Channel id -> display name, from the <channel> elements. Used to match
     * a playlist channel to its EPG data by name when the ids don't align. */
    fun channelDisplayNames(xml: String): Map<String, String> = parseWithNames(xml).second

    /**
     * Returns the programme airing now and the next one, for a channel.
     * Single linear scan, no sort: XMLTV lists a channel's programmes in
     * chronological order, so the first programme containing now is the
     * current one and the first starting at/after now is the next.
     */
    fun nowAndNext(
        programmes: List<Programme>?,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Programme?, Programme?> {
        if (programmes.isNullOrEmpty()) return null to null
        var now: Programme? = null
        var next: Programme? = null
        for (p in programmes) {
            if (now == null && nowMillis in p.startMillis until p.stopMillis) now = p
            if (next == null && p.startMillis >= nowMillis) next = p
            if (now != null && next != null) break
        }
        return now to next
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            // XMLTV dates look like "20261107200000 +0000".
            xmltvDate.parse(raw.trim())?.time ?: 0L
        } catch (ignored: Exception) {
            0L
        }
    }
}
