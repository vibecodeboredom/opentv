package com.opentv.player

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
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

    fun parse(xml: String): Map<String, List<Programme>> {
        val result = mutableMapOf<String, MutableList<Programme>>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val programmes = doc.getElementsByTagName("programme")
            for (i in 0 until programmes.length) {
                val el = programmes.item(i) as? Element ?: continue
                val channel = el.getAttribute("channel")
                val start = parseDate(el.getAttribute("start"))
                val stop = parseDate(el.getAttribute("stop"))
                val title = el.getElementsByTagName("title").item(0)?.textContent?.trim()
                if (channel.isNotEmpty() && !title.isNullOrEmpty() && stop > start) {
                    result.getOrPut(channel) { mutableListOf() } += Programme(
                        channelId = channel,
                        startMillis = start,
                        stopMillis = stop,
                        title = title
                    )
                }
            }
        } catch (ignored: Exception) {
            // EPG is best-effort; a bad file just means no guide data.
        }
        return result
    }

    /** Channel id -> display name, from the <channel> elements. Used to match
     * a playlist channel to its EPG data by name when the ids don't align. */
    fun channelDisplayNames(xml: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        try {
            val factory = DocumentBuilderFactory.newInstance()
            val builder = factory.newDocumentBuilder()
            val doc = builder.parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
            val channels = doc.getElementsByTagName("channel")
            for (i in 0 until channels.length) {
                val el = channels.item(i) as? Element ?: continue
                val id = el.getAttribute("id")
                val name = el.getElementsByTagName("display-name").item(0)
                    ?.textContent?.trim()
                if (id.isNotEmpty() && !name.isNullOrEmpty()) {
                    result[id] = name
                }
            }
        } catch (ignored: Exception) {
            // EPG is best-effort.
        }
        return result
    }

    /** Returns the programme airing now and the next one, for a channel. */
    fun nowAndNext(
        programmes: List<Programme>?,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Programme?, Programme?> {
        if (programmes.isNullOrEmpty()) return null to null
        val sorted = programmes.sortedBy { it.startMillis }
        val now = sorted.firstOrNull { nowMillis in it.startMillis until it.stopMillis }
        val next = sorted.firstOrNull { it.startMillis >= nowMillis }
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
