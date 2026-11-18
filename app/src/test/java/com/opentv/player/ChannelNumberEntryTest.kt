package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.0.0: channel-number entry — digits typed on the remote. */
class ChannelNumberEntryTest {

    @Test
    fun pressAccumulatesDigits() {
        val entry = ChannelNumberEntry()
        assertEquals("1", entry.press(1))
        assertEquals("12", entry.press(2))
        assertEquals(12, entry.value())
    }

    @Test
    fun valueNullWhenEmpty() {
        val entry = ChannelNumberEntry()
        assertTrue(entry.isEmpty())
        assertNull(entry.value())
        assertEquals("", entry.text())
    }

    @Test
    fun pressCapsAtFourDigits() {
        val entry = ChannelNumberEntry()
        entry.press(1)
        entry.press(2)
        entry.press(3)
        entry.press(4)
        assertEquals("1234", entry.press(5))
        assertEquals(1234, entry.value())
    }

    @Test
    fun clearEmpties() {
        val entry = ChannelNumberEntry()
        entry.press(1)
        entry.press(2)
        entry.clear()
        assertTrue(entry.isEmpty())
        assertNull(entry.value())
    }

    @Test(expected = IllegalArgumentException::class)
    fun pressRejectsOutOfRangeDigit() {
        ChannelNumberEntry().press(10)
    }
}
