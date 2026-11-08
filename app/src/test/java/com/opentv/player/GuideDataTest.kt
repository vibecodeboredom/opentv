package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideDataTest {

    private val hour = 60L * 60 * 1000

    private fun channel(id: String, name: String) =
        Channel(id = id, name = name, url = "https://example.com/$id.m3u8", group = "Test", logo = null, tvgId = id)

    @Test
    fun windowStartsAtTopOfHour() {
        // 2026-11-07 20:30 UTC -> window starts 20:00 UTC.
        val now = 1_794_083_400_000L
        assertEquals(1_794_081_600_000L, GuideData.windowStartMillis(now))
    }

    @Test
    fun buildRowsOnlyIncludesChannelsWithEpgInWindow() {
        val windowStart = 1_794_081_600_000L // 2026-11-07 20:00 UTC
        val cnn = channel("CNN.us", "CNN")
        val espn = channel("ESPN.us", "ESPN")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart, windowStart + hour, "News")
            )
            // ESPN has no EPG at all.
        )
        val rows = GuideData.buildRows(listOf(cnn, espn), epg, windowStart, 6)
        assertEquals(1, rows.size)
        assertEquals("CNN", rows[0].channel.name)
        assertEquals("News", rows[0].programmes[0].title)
    }

    @Test
    fun buildRowsExcludesProgrammesOutsideWindow() {
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val epg = mapOf(
            "CNN.us" to listOf(
                // Ends exactly at window start: outside.
                EpgParser.Programme("CNN.us", windowStart - hour, windowStart, "Old Show"),
                // Starts after the 6h window: outside.
                EpgParser.Programme("CNN.us", windowStart + 7 * hour, windowStart + 8 * hour, "Late Show")
            )
        )
        val rows = GuideData.buildRows(listOf(cnn), epg, windowStart, 6)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun layoutCellClampsToWindow() {
        val windowStart = 1_794_081_600_000L
        val windowEnd = windowStart + 6 * hour
        // Programme started 30 min before the window, ends 30 min in.
        val p = EpgParser.Programme("CNN.us", windowStart - 30 * 60_000, windowStart + 30 * 60_000, "News")
        val layout = GuideData.layoutCell(p, windowStart, windowEnd)
        assertEquals(0L, layout.startOffsetMinutes)
        assertEquals(30L, layout.widthMinutes)
    }

    @Test
    fun layoutCellKeepsFullProgrammeInsideWindow() {
        val windowStart = 1_794_081_600_000L
        val windowEnd = windowStart + 6 * hour
        val p = EpgParser.Programme("CNN.us", windowStart + hour, windowStart + 2 * hour, "Show")
        val layout = GuideData.layoutCell(p, windowStart, windowEnd)
        assertEquals(60L, layout.startOffsetMinutes)
        assertEquals(60L, layout.widthMinutes)
    }
}
