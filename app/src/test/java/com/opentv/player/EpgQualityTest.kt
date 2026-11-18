package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.2.0: EPG quality — source status, missing-guide suggestions, coverage. */
class EpgQualityTest {

    private fun channel(id: String, name: String, tvgId: String? = null) =
        Channel(
            id = id, name = name, url = "http://example.com/$id", group = "General",
            logo = null, tvgId = tvgId
        )

    private fun programme(id: String, start: Long, stop: Long) =
        EpgParser.Programme(id, start, stop, "Show")

    private val now = 1_700_000_000_000L

    // ---- Source status -----------------------------------------------

    @Test
    fun encodeDecodeRoundTrip() {
        val statuses = listOf(
            EpgQuality.SourceStatus(
                "https://a.example.com/guide.xml", now, true, null, 12, 34
            ),
            EpgQuality.SourceStatus(
                "https://b.example.com/guide.xml", now - 3_600_000L, false,
                "HTTP 404", 0, 0
            )
        )
        assertEquals(statuses, EpgQuality.decodeStatuses(EpgQuality.encodeStatuses(statuses)))
    }

    @Test
    fun encodeFlattensErrorNewlines() {
        val status = EpgQuality.SourceStatus(
            "https://a.example.com/guide.xml", now, false,
            "HTTP 404\nfor https://a.example.com/guide.xml", 0, 0
        )
        val decoded = EpgQuality.decodeStatuses(EpgQuality.encodeStatuses(listOf(status)))
        assertEquals(1, decoded.size)
        assertEquals("HTTP 404 for https://a.example.com/guide.xml", decoded[0].lastError)
    }

    @Test
    fun decodeSkipsMalformedLines() {
        val raw = "https://a.example.com/guide.xml\t$now\t1\t2\t3\n" +
            "not-a-status-line\n" +
            "https://b.example.com/guide.xml\t${now - 1000}\t0\t0\t0\tHTTP 500"
        val decoded = EpgQuality.decodeStatuses(raw)
        assertEquals(2, decoded.size)
        assertEquals("https://a.example.com/guide.xml", decoded[0].url)
        assertEquals("HTTP 500", decoded[1].lastError)
    }

    @Test
    fun decodeBlankIsEmpty() {
        assertTrue(EpgQuality.decodeStatuses("").isEmpty())
        assertTrue(EpgQuality.decodeStatuses("   ").isEmpty())
    }

    @Test
    fun statusLineNeverFetched() {
        assertEquals("Not fetched yet", EpgQuality.statusLine(null, now))
        val never = EpgQuality.SourceStatus("https://a.example.com/g.xml", 0L, true, null, 0, 0)
        assertEquals("Not fetched yet", EpgQuality.statusLine(never, now))
    }

    @Test
    fun statusLineLoaded() {
        val status = EpgQuality.SourceStatus(
            "https://a.example.com/g.xml", now - 5 * 60_000L, true, null, 12, 34
        )
        assertEquals(
            "Loaded 5 min ago · 12 channels, 34 programmes",
            EpgQuality.statusLine(status, now)
        )
    }

    @Test
    fun statusLineFailed() {
        val status = EpgQuality.SourceStatus(
            "https://a.example.com/g.xml", now - 2 * 3_600_000L, false, "HTTP 404", 0, 0
        )
        assertEquals("Failed 2 h ago — HTTP 404", EpgQuality.statusLine(status, now))
    }

    // ---- Channels without guide data ---------------------------------

    @Test
    fun channelsWithoutGuide() {
        val channels = listOf(
            channel("c1", "CNN", "cnn.us"),
            channel("c2", "BBC One", "nope"),
            channel("c3", "Fox", "nope"),
            channel("c4", "Mystery", "unknown"),
            channel("c5", "Hidden One", "unknown2")
        )
        val epg = mapOf(
            "cnn.us" to listOf(programme("cnn.us", now, now + 1_800_000L)),
            "bbc one" to listOf(programme("bbc one", now, now + 1_800_000L)),
            "foxnews" to listOf(programme("foxnews", now, now + 1_800_000L))
        )
        val missing = EpgQuality.channelsWithoutGuide(
            channels, epg,
            epgOverrides = mapOf("c3" to "foxnews"),
            hidden = setOf("c5")
        )
        assertEquals(listOf("c4"), missing.map { it.id })
    }

    // ---- Suggestions --------------------------------------------------

    @Test
    fun normalizeIdStripsCaseAndPunctuation() {
        assertEquals("cnnus", EpgQuality.normalizeId("CNN.us"))
        assertEquals("cnninternational", EpgQuality.normalizeId("CNN International"))
    }

    @Test
    fun suggestCaseOnlyDifference() {
        val keys = linkedSetOf("cnn_us", "other")
        assertEquals(
            listOf("cnn_us"),
            EpgQuality.suggestEpgIds(channel("c1", "CNN", "CNN.us"), keys)
        )
    }

    @Test
    fun suggestSuffixDifference() {
        val keys = linkedSetOf("cnn.us", "bbc")
        assertEquals(
            listOf("cnn.us"),
            EpgQuality.suggestEpgIds(channel("c1", "CNN", "cnn"), keys)
        )
    }

    @Test
    fun suggestDisplayNameMatch() {
        val keys = linkedSetOf("cnn international", "bbc")
        assertEquals(
            listOf("cnn international"),
            EpgQuality.suggestEpgIds(
                channel("c1", "CNN International", "cnn-intl"), keys
            )
        )
    }

    @Test
    fun suggestContainment() {
        val keys = linkedSetOf("cnn international", "bbc")
        assertEquals(
            listOf("cnn international"),
            EpgQuality.suggestEpgIds(channel("c1", "CNN"), keys)
        )
    }

    @Test
    fun suggestNoMatchIsEmpty() {
        assertTrue(
            EpgQuality.suggestEpgIds(
                channel("c1", "Zebra", "zebra"), linkedSetOf("cnn", "bbc")
            ).isEmpty()
        )
        assertTrue(
            EpgQuality.suggestEpgIds(channel("c1", "Zebra"), emptySet()).isEmpty()
        )
    }

    @Test
    fun suggestCapsAtFive() {
        val keys = linkedSetOf(
            "news one", "news two", "news three", "news four",
            "news five", "news six", "news seven"
        )
        assertEquals(5, EpgQuality.suggestEpgIds(channel("c1", "News"), keys).size)
    }

    @Test
    fun suggestDoesNotStripSuffixWithoutSeparator() {
        // "funk" ends in "uk" but with no separator — it must not
        // match "fn".
        assertTrue(
            EpgQuality.suggestEpgIds(
                channel("c1", "Funk"), linkedSetOf("fn")
            ).isEmpty()
        )
    }

    // ---- Coverage ------------------------------------------------------

    @Test
    fun coverageEmpty() {
        val coverage = EpgQuality.coverageFor(channel("c1", "Nope"), emptyMap(), nowMillis = now)
        assertEquals(EpgQuality.Coverage(0, false, null), coverage)
        assertEquals("Guide: no data for this channel", EpgQuality.coverageLine(coverage, now))
    }

    @Test
    fun coverageOnNow() {
        val epg = mapOf(
            "cnn.us" to listOf(programme("cnn.us", now - 600_000L, now + 600_000L))
        )
        val coverage = EpgQuality.coverageFor(
            channel("c1", "CNN", "cnn.us"), epg, nowMillis = now
        )
        assertEquals(EpgQuality.Coverage(1, true, null), coverage)
        assertTrue(EpgQuality.coverageLine(coverage, now).contains("on now"))
    }

    @Test
    fun coverageFutureOnly() {
        val start = now + 3_600_000L
        val epg = mapOf(
            "cnn.us" to listOf(programme("cnn.us", start, start + 1_800_000L))
        )
        val coverage = EpgQuality.coverageFor(
            channel("c1", "CNN", "cnn.us"), epg, nowMillis = now
        )
        assertEquals(EpgQuality.Coverage(1, false, start), coverage)
        assertTrue(EpgQuality.coverageLine(coverage, now).contains("next "))
    }

    @Test
    fun coveragePastOnlyCountsZero() {
        val epg = mapOf(
            "cnn.us" to listOf(programme("cnn.us", now - 7_200_000L, now - 3_600_000L))
        )
        val coverage = EpgQuality.coverageFor(
            channel("c1", "CNN", "cnn.us"), epg, nowMillis = now
        )
        assertEquals(EpgQuality.Coverage(0, false, null), coverage)
    }

    @Test
    fun coverageOutsideWindowCountsZero() {
        val start = now + 25 * 3_600_000L
        val epg = mapOf(
            "cnn.us" to listOf(programme("cnn.us", start, start + 1_800_000L))
        )
        val coverage = EpgQuality.coverageFor(
            channel("c1", "CNN", "cnn.us"), epg, nowMillis = now
        )
        assertEquals(EpgQuality.Coverage(0, false, null), coverage)
    }

    @Test
    fun statusLineFailedWithoutError() {
        val status = EpgQuality.SourceStatus(
            "https://a.example.com/g.xml", now - 3_600_000L, false, null, 0, 0
        )
        assertEquals("Failed 1 h ago", EpgQuality.statusLine(status, now))
    }

    @Test
    fun coverageMultipleProgrammesMixed() {
        val epg = mapOf(
            "test" to listOf(
                programme("p1", now - 7_200_000L, now - 3_600_000L), // past
                programme("p2", now - 600_000L, now + 600_000L), // on now
                programme("p3", now + 1_000_000L, now + 2_000_000L), // future 1
                programme("p4", now + 5_000_000L, now + 6_000_000L) // future 2
            )
        )
        val coverage = EpgQuality.coverageFor(channel("c1", "Test"), epg, nowMillis = now)
        assertEquals(EpgQuality.Coverage(3, true, now + 1_000_000L), coverage)
    }

    @Test
    fun coverageLineFutureOnlyWithoutNextStart() {
        val coverage = EpgQuality.Coverage(2, false, null)
        assertEquals(
            "Guide: 2 programmes in the next 24h",
            EpgQuality.coverageLine(coverage, now)
        )
    }
}
