package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 7.1.0: sleep timer, playback speed, recording seek skip. */
class PlaybackComfortTest {

    @Test
    fun sleepDelayIsMinutesInMillis() {
        assertEquals(15 * 60_000L, PlaybackComfort.sleepDelayMillis(15))
        assertEquals(30 * 60_000L, PlaybackComfort.sleepDelayMillis(30))
        assertEquals(60 * 60_000L, PlaybackComfort.sleepDelayMillis(60))
        assertEquals(90 * 60_000L, PlaybackComfort.sleepDelayMillis(90))
    }

    @Test(expected = IllegalArgumentException::class)
    fun sleepDelayRejectsNonPositive() {
        PlaybackComfort.sleepDelayMillis(0)
    }

    @Test
    fun sleepLabelOffWhenNull() {
        assertEquals("Off", PlaybackComfort.sleepLabel(null))
        assertEquals("30 min", PlaybackComfort.sleepLabel(30))
    }

    @Test
    fun sleepChoicesAreFour() {
        assertEquals(listOf(15, 30, 60, 90), PlaybackComfort.SLEEP_MINUTES)
    }

    @Test
    fun speedLabels() {
        assertEquals("0.5×", PlaybackComfort.speedLabel(0.5f))
        assertEquals("0.75×", PlaybackComfort.speedLabel(0.75f))
        assertEquals("1×", PlaybackComfort.speedLabel(1f))
        assertEquals("1.25×", PlaybackComfort.speedLabel(1.25f))
        assertEquals("1.5×", PlaybackComfort.speedLabel(1.5f))
        assertEquals("2×", PlaybackComfort.speedLabel(2f))
    }

    @Test
    fun speedChoicesSpanHalfToDouble() {
        assertEquals(listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f), PlaybackComfort.SPEEDS)
    }

    @Test
    fun seekSkipIsTenSeconds() {
        assertEquals(10_000L, PlaybackComfort.SEEK_SKIP_MS)
    }

    @Test
    fun clampSeekKeepsInRange() {
        assertEquals(50_000L, PlaybackComfort.clampSeek(50_000L, 120_000L))
    }

    @Test
    fun clampSeekFloorsAtZero() {
        assertEquals(0L, PlaybackComfort.clampSeek(-5_000L, 120_000L))
    }

    @Test
    fun clampSeekCapsAtDuration() {
        assertEquals(120_000L, PlaybackComfort.clampSeek(130_000L, 120_000L))
    }

    @Test
    fun clampSeekWithUnknownDurationOnlyFloors() {
        assertEquals(0L, PlaybackComfort.clampSeek(-1L, 0L))
        assertEquals(999_999L, PlaybackComfort.clampSeek(999_999L, -1L))
    }

    // Extra cases from a Gemini review pass (7.1.0).
    @Test(expected = IllegalArgumentException::class)
    fun sleepDelayRejectsNegative() {
        PlaybackComfort.sleepDelayMillis(-15)
    }

    @Test
    fun clampSeekExactBoundaries() {
        assertEquals(0L, PlaybackComfort.clampSeek(0L, 100L))
        assertEquals(100L, PlaybackComfort.clampSeek(100L, 100L))
        assertEquals(0L, PlaybackComfort.clampSeek(0L, 0L))
    }

    @Test
    fun clampSeekNegativeDurationWithPositivePosition() {
        assertEquals(50L, PlaybackComfort.clampSeek(50L, -100L))
    }
}
