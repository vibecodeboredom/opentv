package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // 7.0.0: channel-number jump.

    private fun numbered(id: String, number: Int) =
        Channel(
            id = id, name = id, url = "https://example.com/$id.m3u8",
            group = "Test", logo = null, tvgId = id, number = number
        )

    @Test
    fun channelByNumberFindsByNumber() {
        val channels = listOf(numbered("a", 1), numbered("b", 5), numbered("c", 12))
        assertEquals("b", GuideData.channelByNumber(channels, 5)?.id)
    }

    @Test
    fun channelByNumberReturnsNullWhenMissing() {
        val channels = listOf(numbered("a", 1), numbered("b", 5))
        assertEquals(null, GuideData.channelByNumber(channels, 7))
    }

    @Test
    fun channelByNumberFirstMatchWinsOnDuplicates() {
        val channels = listOf(numbered("a", 5), numbered("b", 5))
        assertEquals("a", GuideData.channelByNumber(channels, 5)?.id)
    }

    // 7.0.0: EPG freshness label.

    @Test
    fun formatAgeJustNow() {
        val now = 1_794_081_600_000L
        assertEquals("just now", GuideData.formatAge(now, now))
        assertEquals("just now", GuideData.formatAge(now - 30_000L, now))
    }

    @Test
    fun formatAgeFutureReadsAsJustNow() {
        val now = 1_794_081_600_000L
        assertEquals("just now", GuideData.formatAge(now + 60_000L, now))
    }

    @Test
    fun formatAgeMinutes() {
        val now = 1_794_081_600_000L
        assertEquals("5 min ago", GuideData.formatAge(now - 5 * 60_000L, now))
        assertEquals("59 min ago", GuideData.formatAge(now - 59 * 60_000L, now))
    }

    @Test
    fun formatAgeHours() {
        val now = 1_794_081_600_000L
        assertEquals("2 h ago", GuideData.formatAge(now - 2 * hour, now))
        assertEquals("23 h ago", GuideData.formatAge(now - 23 * hour, now))
    }

    @Test
    fun formatAgeDays() {
        val now = 1_794_081_600_000L
        assertEquals("3 d ago", GuideData.formatAge(now - 3 * 24 * hour, now))
    }

    // 7.2.0: day navigation + Tonight. tonightWindow uses local-time
    // Calendar, so these tests pin the default timezone to UTC.
    private fun withUtc(block: () -> Unit) {
        val original = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            block()
        } finally {
            java.util.TimeZone.setDefault(original)
        }
    }

    @Test
    fun dayAnchorTodayIsTopOfHour() {
        // 2026-11-07 20:30 UTC -> anchor 20:00 UTC.
        val now = 1_794_083_400_000L
        assertEquals(1_794_081_600_000L, GuideData.dayAnchorMillis(now, 0))
    }

    @Test
    fun dayAnchorTomorrowAdds24Hours() {
        val now = 1_794_083_400_000L
        assertEquals(1_794_081_600_000L + 24 * hour, GuideData.dayAnchorMillis(now, 1))
    }

    @Test
    fun dayAnchorYesterdaySubtracts24Hours() {
        val now = 1_794_083_400_000L
        assertEquals(1_794_081_600_000L - 24 * hour, GuideData.dayAnchorMillis(now, -1))
    }

    @Test
    fun tonightWindowMorningIsToday6To11Pm() {
        withUtc {
            // 2026-11-07 10:00 UTC -> tonight 18:00–23:00 UTC.
            val (start, hours) = GuideData.tonightWindow(1_794_045_600_000L)
            assertEquals(1_794_074_400_000L, start)
            assertEquals(5, hours)
        }
    }

    @Test
    fun tonightWindowEveningIsStillToday6To11Pm() {
        withUtc {
            // 2026-11-07 20:30 UTC -> still tonight 18:00–23:00 UTC.
            val (start, hours) = GuideData.tonightWindow(1_794_083_400_000L)
            assertEquals(1_794_074_400_000L, start)
            assertEquals(5, hours)
        }
    }

    @Test
    fun tonightWindowAfter11PmIsStillToday() {
        withUtc {
            // 2026-11-07 23:30 UTC -> still today 18:00–23:00 UTC
            // (the prime time that just aired, not tomorrow's).
            val (start, hours) = GuideData.tonightWindow(1_794_094_200_000L)
            assertEquals(1_794_074_400_000L, start)
            assertEquals(5, hours)
        }
    }

    @Test
    fun tonightWindowAt11PmIsStillToday() {
        withUtc {
            // 2026-11-07 23:00 UTC exactly -> still today 18:00 UTC.
            val (start, hours) = GuideData.tonightWindow(1_794_092_400_000L)
            assertEquals(1_794_074_400_000L, start)
            assertEquals(5, hours)
        }
    }

    @Test
    fun dayAnchorPlus6IsTheMaxDayOffset() {
        // 2026-11-07 10:00 UTC -> anchor +6 days.
        val now = 1_794_045_600_000L
        assertEquals(1_794_045_600_000L + 6 * 24 * hour, GuideData.dayAnchorMillis(now, 6))
    }

    @Test
    fun tonightWindowAtExactly6Pm() {
        withUtc {
            // 2026-11-07 18:00 UTC exactly -> tonight starts now.
            val (start, hours) = GuideData.tonightWindow(1_794_074_400_000L)
            assertEquals(1_794_074_400_000L, start)
            assertEquals(5, hours)
        }
    }

    @Test
    fun dayAnchorAtMidnight() {
        // 2026-11-07 00:00 UTC -> anchor is midnight itself.
        val now = 1_794_009_600_000L
        assertEquals(1_794_009_600_000L, GuideData.dayAnchorMillis(now, 0))
    }

    // 7.2.1: Tonight empty states — anyRealProgrammes is the check
    // behind "favorites exist but nothing is on tonight".

    @Test
    fun anyRealProgrammesTrueWhenProgrammeOverlapsWindow() {
        val windowStart = 1_794_081_600_000L // 2026-11-07 20:00 UTC
        val cnn = channel("CNN.us", "CNN")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart + hour, windowStart + 2 * hour, "News")
            )
        )
        assertTrue(GuideData.anyRealProgrammes(listOf(cnn), epg, windowStart, 5))
    }

    @Test
    fun anyRealProgrammesFalseWhenProgrammesOutsideWindow() {
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val epg = mapOf(
            "CNN.us" to listOf(
                // Ends exactly at window start: outside.
                EpgParser.Programme("CNN.us", windowStart - hour, windowStart, "Old Show"),
                // Starts after the 5h window: outside.
                EpgParser.Programme("CNN.us", windowStart + 6 * hour, windowStart + 7 * hour, "Late Show")
            )
        )
        assertFalse(GuideData.anyRealProgrammes(listOf(cnn), epg, windowStart, 5))
    }

    @Test
    fun anyRealProgrammesFalseWithNoEpg() {
        val windowStart = 1_794_081_600_000L
        val espn = channel("ESPN.us", "ESPN")
        assertFalse(GuideData.anyRealProgrammes(listOf(espn), emptyMap(), windowStart, 5))
    }

    @Test
    fun anyRealProgrammesFalseWithNoChannels() {
        val windowStart = 1_794_081_600_000L
        assertFalse(GuideData.anyRealProgrammes(emptyList(), emptyMap(), windowStart, 5))
    }

    @Test
    fun anyRealProgrammesUsesEpgOverride() {
        val windowStart = 1_794_081_600_000L
        val ch = channel("custom.1", "Custom")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart, windowStart + hour, "News")
            )
        )
        assertTrue(
            GuideData.anyRealProgrammes(
                listOf(ch), epg, windowStart, 5,
                epgOverrides = mapOf("custom.1" to "CNN.us")
            )
        )
    }

    @Test
    fun anyRealProgrammesTrueWhenOnlySecondChannelHasProgrammeInWindow() {
        // Gemini-generated edge case, restyled to match this file.
        val windowStart = 1_794_081_600_000L
        val cnn = channel("CNN.us", "CNN")
        val espn = channel("ESPN.us", "ESPN")
        val epg = mapOf(
            "CNN.us" to listOf(
                EpgParser.Programme("CNN.us", windowStart - 4 * hour, windowStart - 2 * hour, "Too Early")
            ),
            "ESPN.us" to listOf(
                EpgParser.Programme("ESPN.us", windowStart + hour, windowStart + 3 * hour, "Inside Window")
            )
        )
        assertTrue(GuideData.anyRealProgrammes(listOf(cnn, espn), epg, windowStart, 6))
    }

    // 7.2.1: window-size layout — the header/row/now-line math must
    // hold at the 24 h size, not just 6 h.

    @Test
    fun layoutCellAt24hWindowStaysInsideWindow() {
        val windowStart = 1_794_081_600_000L
        val windowEnd = windowStart + 24 * hour
        // A programme near the far end of a 24 h window.
        val p = EpgParser.Programme(
            "CNN.us", windowStart + 22 * hour, windowStart + 23 * hour, "Late Movie"
        )
        val layout = GuideData.layoutCell(p, windowStart, windowEnd)
        assertEquals(22 * 60L, layout.startOffsetMinutes)
        assertEquals(60L, layout.widthMinutes)
        assertTrue(layout.startOffsetMinutes + layout.widthMinutes <= 24 * 60L)
    }

    @Test
    fun layoutCellAt24hWindowClampsProgrammePastTheEnd() {
        val windowStart = 1_794_081_600_000L
        val windowEnd = windowStart + 24 * hour
        // Starts inside, runs past the window end.
        val p = EpgParser.Programme(
            "CNN.us", windowStart + 23 * hour, windowStart + 25 * hour, "Long Movie"
        )
        val layout = GuideData.layoutCell(p, windowStart, windowEnd)
        assertEquals(23 * 60L, layout.startOffsetMinutes)
        assertEquals(60L, layout.widthMinutes)
    }

    @Test
    fun tonightWindowIsFiveHoursRegardlessOfWindowSize() {
        // Tonight is a fixed 6–11 PM window — the 6/12/24 h guide
        // window size never stretches it.
        withUtc {
            val now = 1_794_083_400_000L // 2026-11-07 20:30 UTC
            val (start, hours) = GuideData.tonightWindow(now)
            assertEquals(5, hours)
            // 6 PM is 2 hours before the 8 PM top-of-hour anchor.
            assertEquals(GuideData.dayAnchorMillis(now, 0) - 2 * hour, start)
        }
    }
}
