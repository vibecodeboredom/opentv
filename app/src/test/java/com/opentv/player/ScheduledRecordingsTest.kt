package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.3.0: scheduled recordings — encode/decode, pruning, lookup. */
class ScheduledRecordingsTest {

    private fun sample(
        channelId: String = "ch-cnn",
        start: Long = 1_000_000L,
        stop: Long = 1_360_000L
    ) = ScheduledRecordings.ScheduledRecording(
        channelId = channelId,
        channelName = "CNN",
        channelUrl = "http://example.com/cnn.m3u8",
        programmeTitle = "Evening News",
        startMillis = start,
        stopMillis = stop
    )

    @Test
    fun roundTripKeepsEverything() {
        val list = listOf(sample(), sample("ch-bbc", 2_000_000L, 2_360_000L))
        assertEquals(list, ScheduledRecordings.decode(ScheduledRecordings.encode(list)))
    }

    @Test
    fun idIsStableAcrossEncodeDecode() {
        val rec = sample()
        val decoded = ScheduledRecordings.decode(ScheduledRecordings.encode(listOf(rec))).single()
        assertEquals(rec.id, decoded.id)
    }

    @Test
    fun idDiffersForDifferentStartTimes() {
        val a = sample(start = 1_000_000L)
        val b = sample(start = 2_000_000L)
        assertFalse(a.id == b.id)
    }

    @Test
    fun malformedLinesAreSkippedNotFatal() {
        val good = sample()
        val raw = "garbage\n" +
            ScheduledRecordings.encode(listOf(good)) + "\n" +
            "ch-x\u0001Name\u0001http://x\u0001Title\u0001not-a-number\u0001123\n" +
            "ch-y\u0001Name\u0001http://y\u0001Title\u0001100\u0001100\n" +
            "ch-z\u0001Name\u0001\u0001Title\u0001100\u0001200"
        val decoded = ScheduledRecordings.decode(raw)
        assertEquals(listOf(good), decoded)
    }

    @Test
    fun newlineInTitleIsCleanedOnEncode() {
        val rec = sample().copy(programmeTitle = "News\nTonight")
        val decoded = ScheduledRecordings.decode(ScheduledRecordings.encode(listOf(rec))).single()
        assertEquals("News Tonight", decoded.programmeTitle)
        assertEquals(rec.copy(programmeTitle = "News Tonight"), decoded)
    }

    @Test
    fun upcomingDropsEndedProgrammesAndSortsByStart() {
        val now = 5_000_000L
        val past = sample("ch-old", 1_000_000L, 2_000_000L)
        val later = sample("ch-late", 9_000_000L, 9_360_000L)
        val sooner = sample("ch-soon", 6_000_000L, 6_360_000L)
        val upcoming = ScheduledRecordings.upcoming(listOf(later, past, sooner), now)
        assertEquals(listOf(sooner, later), upcoming)
    }

    @Test
    fun programmeEndingExactlyNowIsNotUpcoming() {
        val rec = sample(start = 4_000_000L, stop = 5_000_000L)
        assertTrue(ScheduledRecordings.upcoming(listOf(rec), 5_000_000L).isEmpty())
    }

    @Test
    fun isScheduledMatchesChannelAndStart() {
        val list = listOf(sample())
        assertTrue(ScheduledRecordings.isScheduled(list, "ch-cnn", 1_000_000L))
        assertFalse(ScheduledRecordings.isScheduled(list, "ch-cnn", 2_000_000L))
        assertFalse(ScheduledRecordings.isScheduled(list, "ch-bbc", 1_000_000L))
    }

    @Test
    fun findReturnsTheMatchingSchedule() {
        val list = listOf(sample(), sample("ch-bbc", 2_000_000L, 2_360_000L))
        assertEquals(sample("ch-bbc", 2_000_000L, 2_360_000L), ScheduledRecordings.find(list, "ch-bbc", 2_000_000L))
        assertEquals(null, ScheduledRecordings.find(list, "ch-bbc", 9_000_000L))
    }

    @Test
    fun withoutIdRemovesOnlyThatSchedule() {
        val a = sample()
        val b = sample("ch-bbc", 2_000_000L, 2_360_000L)
        assertEquals(listOf(b), ScheduledRecordings.withoutId(listOf(a, b), a.id))
        assertEquals(listOf(a, b), ScheduledRecordings.withoutId(listOf(a, b), "sched_nope_0"))
    }

    @Test
    fun decodeSkipsStopBeforeOrAtStart() {
        val invalidStop = "ch-1\u0001Name\u0001http://ex.com\u0001Title\u00012000\u00011000"
        val equalStop = "ch-2\u0001Name\u0001http://ex.com\u0001Title\u00012000\u00012000"
        val raw = "$invalidStop\n$equalStop"
        assertTrue(ScheduledRecordings.decode(raw).isEmpty())
    }

    @Test
    fun cleanFieldReplacesSpecialCharacters() {
        // Gemini wrote this test with double spaces in the
        // expectation; each special char becomes exactly one space,
        // so the correct expectation has single spaces.
        val dirty = "Title\nwith\rnewlines\tand\u0001separator\tand tabs\t"
        val cleaned = ScheduledRecordings.cleanField(dirty)
        assertEquals("Title with newlines and separator and tabs", cleaned)
    }

    @Test
    fun emptyTextDecodesToEmptyList() {
        assertEquals(emptyList<ScheduledRecordings.ScheduledRecording>(), ScheduledRecordings.decode(""))
        assertEquals(emptyList<ScheduledRecordings.ScheduledRecording>(), ScheduledRecordings.decode("   "))
    }
}
