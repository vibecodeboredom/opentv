package com.opentv.player

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal XMLTV parser. Only keeps programme titles with start/stop times,
 * keyed by channel id, so the player can show "Now" / "Next".
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
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            var event = parser.eventType
            var currentChannel: String? = null
            var currentStart = 0L
            var currentStop = 0L
            var currentTitle: String? = null
            var inTitle = false
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "programme" -> {
                            currentChannel = parser.getAttributeValue(null, "channel")
                            currentStart = parseDate(parser.getAttributeValue(null, "start"))
                            currentStop = parseDate(parser.getAttributeValue(null, "stop"))
                            currentTitle = null
                        }
                        "title" -> inTitle = true
                    }
                    XmlPullParser.TEXT -> if (inTitle) {
                        currentTitle = (currentTitle ?: "") + parser.text
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "title" -> inTitle = false
                        "programme" -> {
                            val channel = currentChannel
                            val title = currentTitle?.trim()
                            if (channel != null && title != null && currentStop > currentStart) {
                                result.getOrPut(channel) { mutableListOf() } += Programme(
                                    channelId = channel,
                                    startMillis = currentStart,
                                    stopMillis = currentStop,
                                    title = title
                                )
                            }
                            currentChannel = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (ignored: Exception) {
            // EPG is best-effort; a bad file just means no guide data.
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
