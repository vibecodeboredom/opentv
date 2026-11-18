package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 8.3.0: recordings list sort/filter and auto-delete selection. */
class RecordingLibraryTest {

    private fun rec(
        id: String,
        name: String = "CNN",
        started: Long = 1_000_000L,
        bytes: Long = 100L,
        inProgress: Boolean = false
    ) = Recording(
        id = id,
        channelName = name,
        uri = "http://example.com/$id.m3u8",
        startedAtMillis = started,
        bytesDownloaded = bytes,
        inProgress = inProgress
    )

    private val newest = rec("r-new", started = 3_000_000L, bytes = 300L)
    private val middle = rec("r-mid", name = "BBC", started = 2_000_000L, bytes = 200L)
    private val oldest = rec("r-old", name = "Al Jazeera", started = 1_000_000L, bytes = 100L)
    private val live = rec("r-live", name = "CNN Live", started = 4_000_000L, bytes = 50L, inProgress = true)

    @Test
    fun newestFirstIsDefaultOrder() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(oldest, newest, middle), RecordingLibrary.Sort.NEWEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(newest, middle, oldest), out)
    }

    @Test
    fun oldestFirstReversesDateOrder() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(newest, oldest, middle), RecordingLibrary.Sort.OLDEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(oldest, middle, newest), out)
    }

    @Test
    fun largestFirstSortsByBytes() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(oldest, newest, middle), RecordingLibrary.Sort.LARGEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(newest, middle, oldest), out)
    }

    @Test
    fun smallestFirstSortsByBytesAscending() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(newest, middle, oldest), RecordingLibrary.Sort.SMALLEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(oldest, middle, newest), out)
    }

    @Test
    fun nameSortIsCaseInsensitive() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(newest, oldest, middle), RecordingLibrary.Sort.NAME, RecordingLibrary.Filter.ALL
        )
        // Al Jazeera < BBC < CNN (case-insensitive).
        assertEquals(listOf(oldest, middle, newest), out)
    }

    @Test
    fun inProgressFilterKeepsOnlyLiveOnes() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(oldest, live, newest), RecordingLibrary.Sort.NEWEST, RecordingLibrary.Filter.IN_PROGRESS
        )
        assertEquals(listOf(live), out)
    }

    @Test
    fun finishedFilterDropsLiveOnes() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(live, oldest, newest), RecordingLibrary.Sort.NEWEST, RecordingLibrary.Filter.FINISHED
        )
        assertEquals(listOf(newest, oldest), out)
    }

    @Test
    fun sortAndFilterWithFinishedFilterOldestFirst() {
        val out = RecordingLibrary.sortAndFilter(
            listOf(live, oldest, newest), RecordingLibrary.Sort.OLDEST, RecordingLibrary.Filter.FINISHED
        )
        assertEquals(listOf(oldest, newest), out)
    }

    @Test
    fun negativeKeepDaysSelectsNothing() {
        val old = rec("r-old", started = 1L)
        assertEquals(
            emptyList<Recording>(),
            RecordingLibrary.oldRecordings(listOf(old), keepDays = -5, nowMillis = 100L)
        )
    }

    @Test
    fun oldestSortPutsDatelessRecordingsLast() {
        val dateless = rec("r-nostart", started = 0L)
        val out = RecordingLibrary.sortAndFilter(
            listOf(dateless, newest, oldest), RecordingLibrary.Sort.OLDEST, RecordingLibrary.Filter.ALL
        )
        // A recording with no start time recorded has an unknown
        // date — it is not the "oldest", it goes last.
        assertEquals(listOf(oldest, newest, dateless), out)
    }

    @Test
    fun newestSortPutsDatelessRecordingsLast() {
        val dateless = rec("r-nostart", started = 0L)
        val out = RecordingLibrary.sortAndFilter(
            listOf(dateless, newest, oldest), RecordingLibrary.Sort.NEWEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(newest, oldest, dateless), out)
    }

    @Test
    fun newestSortMultipleDatelessRecordingsStayIdOrderedAtEnd() {
        val r1 = rec("a", started = 0L)
        val r2 = rec("b", started = 0L)
        val r3 = rec("c", started = 500L)
        val out = RecordingLibrary.sortAndFilter(
            listOf(r1, r2, r3), RecordingLibrary.Sort.NEWEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(r3, r1, r2), out)
    }

    @Test
    fun largestSortDatelessRecordingDoesNotAffectByteSort() {
        val r1 = rec("a", started = 0L, bytes = 50L)
        val r2 = rec("b", started = 1000L, bytes = 10L)
        val r3 = rec("c", started = 2000L, bytes = 100L)
        val out = RecordingLibrary.sortAndFilter(
            listOf(r1, r2, r3), RecordingLibrary.Sort.LARGEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(r3, r1, r2), out)
    }

    @Test
    fun smallestSortDatelessRecordingDoesNotAffectByteSort() {
        val r1 = rec("a", started = 0L, bytes = 50L)
        val r2 = rec("b", started = 1000L, bytes = 10L)
        val r3 = rec("c", started = 2000L, bytes = 100L)
        val out = RecordingLibrary.sortAndFilter(
            listOf(r1, r2, r3), RecordingLibrary.Sort.SMALLEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(r2, r1, r3), out)
    }

    @Test
    fun nameSortNamesDifferingOnlyByCaseFallBackToId() {
        val r1 = rec("b", name = "CNN")
        val r2 = rec("a", name = "cnn")
        val out = RecordingLibrary.sortAndFilter(
            listOf(r1, r2), RecordingLibrary.Sort.NAME, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(r2, r1), out)
    }

    @Test
    fun sortTiesFallBackToIdSoOrderIsStable() {
        val a = rec("r-a", started = 1_000_000L)
        val b = rec("r-b", started = 1_000_000L)
        val out = RecordingLibrary.sortAndFilter(
            listOf(b, a), RecordingLibrary.Sort.NEWEST, RecordingLibrary.Filter.ALL
        )
        assertEquals(listOf(a, b), out)
    }

    @Test
    fun oldRecordingsSelectsOnlyFinishedOnesPastKeepDays() {
        val now = 10L * 24 * 60 * 60 * 1000 // day 10
        val day1 = rec("r-day1", started = 1L * 24 * 60 * 60 * 1000)
        val day8 = rec("r-day8", started = 8L * 24 * 60 * 60 * 1000)
        val liveOld = rec("r-live-old", started = 1L * 24 * 60 * 60 * 1000, inProgress = true)
        val noStart = rec("r-nostart", started = 0L)
        val out = RecordingLibrary.oldRecordings(
            listOf(day1, day8, liveOld, noStart), keepDays = 7, nowMillis = now
        )
        // Day 1 is 9 days old (past the 7-day keep); day 8 is only
        // 2 days old; the live one and the dateless one are never
        // selected.
        assertEquals(listOf(day1), out)
    }

    @Test
    fun keepOfZeroSelectsNothing() {
        val old = rec("r-old", started = 1L)
        assertEquals(
            emptyList<Recording>(),
            RecordingLibrary.oldRecordings(listOf(old), keepDays = 0, nowMillis = 100L)
        )
    }

    @Test
    fun recordingExactlyAtCutoffIsKept() {
        val now = 8L * 24 * 60 * 60 * 1000
        val atCutoff = rec("r-cut", started = 1L * 24 * 60 * 60 * 1000)
        assertEquals(
            emptyList<Recording>(),
            RecordingLibrary.oldRecordings(listOf(atCutoff), keepDays = 7, nowMillis = now)
        )
    }
}
