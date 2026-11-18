package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 7.1.0: playback speed, recording seek skip. (The sleep-timer
 * tests went with the helpers in the combine — the shipped
 * timer is Nova's, in PlaybackActivity.) */
class PlaybackComfortTest {

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

    // 7.1.1: recording seek toast position.
    @Test
    fun formatSeekTimeMinutesAndSeconds() {
        assertEquals("0:00", PlaybackComfort.formatSeekTime(0L))
        assertEquals("0:05", PlaybackComfort.formatSeekTime(5_000L))
        assertEquals("1:05", PlaybackComfort.formatSeekTime(65_000L))
        assertEquals("10:00", PlaybackComfort.formatSeekTime(600_000L))
    }

    @Test
    fun formatSeekTimeHours() {
        assertEquals("1:00:00", PlaybackComfort.formatSeekTime(3_600_000L))
        assertEquals("1:01:01", PlaybackComfort.formatSeekTime(3_661_000L))
    }

    @Test
    fun formatSeekTimeClampsNegative() {
        assertEquals("0:00", PlaybackComfort.formatSeekTime(-5_000L))
    }

    @Test
    fun formatSeekTimeTruncatesMillis() {
        assertEquals("1:05", PlaybackComfort.formatSeekTime(65_999L))
    }
}
