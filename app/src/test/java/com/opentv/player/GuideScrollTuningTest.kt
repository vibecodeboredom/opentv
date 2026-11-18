package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9.2.0: guide scroll tuning — small chunks, early trigger. */
class GuideScrollTuningTest {

    @Test
    fun chunksAreSmallAndTheBufferStaysAhead() {
        assertEquals(8, GuideScrollTuning.APPEND_ROWS)
        assertEquals(12, GuideScrollTuning.FOCUS_BUFFER)
        // The buffer must cover a whole chunk, or the user can
        // outrun the built rows before the next append triggers.
        assertTrue(GuideScrollTuning.FOCUS_BUFFER >= GuideScrollTuning.APPEND_ROWS)
        assertEquals(24, GuideScrollTuning.INITIAL_ROWS)
        assertEquals(12, GuideScrollTuning.FIRST_PAINT_ROWS)
    }

    @Test
    fun appendTriggersInsideTheBuffer() {
        // 24 built, focus on row 12 (the buffer edge) -> append.
        assertTrue(GuideScrollTuning.shouldAppend(24, 12))
        // Focus on row 11 is one row outside the buffer -> no append.
        assertFalse(GuideScrollTuning.shouldAppend(24, 11))
    }

    @Test
    fun appendTriggersAtTheLastBuiltRow() {
        assertTrue(GuideScrollTuning.shouldAppend(8, 7))
        assertTrue(GuideScrollTuning.shouldAppend(8, 8))
    }

    @Test
    fun noAppendForEmptyGridOrBadFocus() {
        assertFalse(GuideScrollTuning.shouldAppend(0, 0))
        assertFalse(GuideScrollTuning.shouldAppend(24, -1))
    }

    // ---- Gemini -------------------------------------------------------

    @Test
    fun focusBeyondBuiltRowsStillAppends() {
        // Focus can run past the built rows (a day jump landing);
        // the append must still trigger.
        assertTrue(GuideScrollTuning.shouldAppend(20, 25))
    }

    @Test
    fun negativeBuiltRowCountNeverAppends() {
        assertFalse(GuideScrollTuning.shouldAppend(-1, 5))
    }
}
