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
    fun buildRowsGivesRealProgrammesWhenEpgExists() {
        val windowStart = 1_794_081_600_000L // 2026-11-07 20:00 UTC
        val cnn = channel("CNN.us", "CNN")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart, windowStart + hour, "News")
            )
        )
        val rows = GuideData.buildRows(listOf(cnn), epg, windowStart, 6)
        assertEquals(1, rows.size)
        assertEquals("CNN", rows[0].channel.name)
        assertEquals("News", rows[0].programmes[0].title)
    }

    @Test
    fun buildRowsFillsNoInformationWhenNoEpg() {
        val windowStart = 1_794_081_600_000L
        val espn = channel("ESPN.us", "ESPN")
        // ESPN has no EPG at all: it still gets a row, with placeholders.
        val rows = GuideData.buildRows(listOf(espn), emptyMap(), windowStart, 6)
        assertEquals(1, rows.size)
        assertEquals("ESPN", rows[0].channel.name)
        assertTrue(rows[0].programmes.isNotEmpty())
        assertTrue(rows[0].programmes.all { it.title == GuideData.NO_INFORMATION })
    }

    @Test
    fun buildRowsFillsNoInformationWhenProgrammesOutsideWindow() {
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
        assertEquals(1, rows.size)
        assertTrue(rows[0].programmes.all { it.title == GuideData.NO_INFORMATION })
    }

    @Test
    fun placeholdersCoverWindowInOneHourBlocks() {
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val list = GuideData.placeholderProgrammes(cnn, windowStart, windowStart + 6 * hour)
        assertEquals(6, list.size)
        assertEquals(windowStart, list[0].startMillis)
        assertEquals(windowStart + 6 * hour, list.last().stopMillis)
        assertTrue(list.all { it.title == GuideData.NO_INFORMATION })
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

    @Test
    fun formatTimeRangeShowsStartAndEnd() {
        // 2026-11-07 20:00 -> 21:00 UTC. (Device timezone may shift the
        // hour, so only check the shape: two clock times joined by a dash.)
        val start = 1_794_081_600_000L
        val end = start + hour
        val range = GuideData.formatTimeRange(start, end)
        assertTrue(range.contains("–"))
        assertTrue(range.contains(":"))
    }

    @Test
    fun buildRowsSkipsHiddenChannels() {
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart, windowStart + hour, "News")
            )
        )
        val rows = GuideData.buildRows(
            listOf(cnn), epg, windowStart, 6, hidden = setOf("CNN.us")
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun buildRowsUsesEpgOverride() {
        val windowStart = 1_794_081_600_000L
        // Channel's own tvg-id has no EPG, but the override points at one that does.
        val ch = channel("custom.1", "Custom")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart, windowStart + hour, "News")
            )
        )
        val rows = GuideData.buildRows(
            listOf(ch), epg, windowStart, 6,
            epgOverrides = mapOf("custom.1" to "CNN.us")
        )
        assertEquals(1, rows.size)
        assertEquals("News", rows[0].programmes[0].title)
    }

    @Test
    fun searchFindsChannelPastThe400RowCap() {
        // The user's exact repro (4.9.0): a channel title that IS in the
        // playlist but sits past the first 400 rows never appeared in
        // search, because the old search only filtered the displayed
        // rows. The new search covers the whole playlist.
        val windowStart = 1_794_081_600_000L
        val channels = (1..450).map { i -> channel("ch.$i", "Channel $i") }
        val rows = GuideData.searchRows(channels, emptyMap(), windowStart, 6, query = "Channel 449")
        assertEquals(1, rows.size)
        assertEquals("Channel 449", rows[0].channel.name)
    }

    @Test
    fun searchFindsProgrammeOutsideTheWindow() {
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val epg = mapOf(
            "CNN.us" to listOf(
                // Airs 7 hours out — outside the 6h guide window.
                EpgParser.Programme("CNN.us", windowStart + 7 * hour, windowStart + 8 * hour, "Late Night Special")
            )
        )
        val rows = GuideData.searchRows(listOf(cnn), epg, windowStart, 6, query = "Late Night")
        assertEquals(1, rows.size)
        assertEquals("CNN", rows[0].channel.name)
    }

    @Test
    fun searchMatchesPartialWords() {
        val windowStart = 1_794_081_600_000L
        val ch = channel("bbc.one", "BBC One")
        // "break" is only part of the title word.
        val rows = GuideData.searchRows(
            listOf(ch),
            mapOf("bbc.one" to listOf(
                EpgParser.Programme("bbc.one", windowStart, windowStart + hour, "Breaking News Tonight")
            )),
            windowStart, 6, query = "break"
        )
        assertEquals(1, rows.size)
    }

    @Test
    fun searchPutsChannelNameMatchesBeforeProgrammeMatches() {
        val windowStart = 1_794_081_600_000L
        val news = channel("news.1", "News Channel")
        val sport = channel("sport.1", "Sports")
        val epg = mapOf(
            "sport.1" to listOf(
                EpgParser.Programme("sport.1", windowStart, windowStart + hour, "News Roundup")
            )
        )
        val rows = GuideData.searchRows(
            listOf(news, sport), epg, windowStart, 6, query = "news"
        )
        assertEquals(2, rows.size)
        assertEquals("News Channel", rows[0].channel.name)
        assertEquals("Sports", rows[1].channel.name)
    }

    @Test
    fun searchSkipsHiddenChannels() {
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val rows = GuideData.searchRows(
            listOf(cnn), emptyMap(), windowStart, 6,
            hidden = setOf("CNN.us"), query = "CNN"
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun searchWordsSplitsAndLowercases() {
        assertEquals(listOf("breaking", "bad"), GuideData.searchWords("  Breaking   BAD "))
        assertTrue(GuideData.searchWords("   ").isEmpty())
    }

    @Test
    fun progressFractionIsHalfway() {
        val start = 1_794_081_600_000L
        val p = EpgParser.Programme("CNN.us", start, start + hour, "News")
        assertEquals(0.5f, GuideData.progressFraction(p, start + hour / 2), 0.001f)
    }

    @Test
    fun remainingMinutesCountsDown() {
        val start = 1_794_081_600_000L
        val p = EpgParser.Programme("CNN.us", start, start + hour, "News")
        assertEquals(12L, GuideData.remainingMinutes(p, start + 48 * 60_000))
    }

    @Test
    fun formatDateTimeIncludesDateAndClock() {
        val text = GuideData.formatDateTime(1_794_081_600_000L)
        assertTrue(text.contains("Nov"))
        assertTrue(text.contains(":"))
    }

    @Test
    fun formatDateShowsWeekdayMonthDay() {
        // 2026-11-07 is a Saturday.
        val date = GuideData.formatDate(1_794_081_600_000L)
        assertTrue(date.contains("Nov"))
        assertTrue(date.contains("7"))
    }
}
