package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9.3.0 (Nova): the Settings PIN record maths — the PIN itself
 * is never stored, only a salted PBKDF2 record, so these tests
 * pin down the round trip and the failure shapes. The Context
 * wrappers in AppSettings are thin get/put around these. */
class SettingsPinTest {

    @Test
    fun recordMatchesItsPin() {
        val record = AppSettings.settingsPinRecord("1234")
        assertTrue(AppSettings.settingsPinMatches(record, "1234"))
    }

    @Test
    fun wrongPinDoesNotMatch() {
        val record = AppSettings.settingsPinRecord("1234")
        assertFalse(AppSettings.settingsPinMatches(record, "1235"))
        assertFalse(AppSettings.settingsPinMatches(record, "123"))
        assertFalse(AppSettings.settingsPinMatches(record, ""))
    }

    @Test
    fun recordsForTheSamePinDiffer() {
        // A fresh salt per record: two records for one PIN are
        // never equal, yet both match it.
        val a = AppSettings.settingsPinRecord("9876")
        val b = AppSettings.settingsPinRecord("9876")
        assertNotEquals(a, b)
        assertTrue(AppSettings.settingsPinMatches(a, "9876"))
        assertTrue(AppSettings.settingsPinMatches(b, "9876"))
    }

    @Test
    fun recordShapeIsSaltAndHash() {
        // "saltHex:hashHex" — 16 salt bytes and a 256-bit PBKDF2
        // output, hex.
        val parts = AppSettings.settingsPinRecord("4321").split(":")
        assertEquals(2, parts.size)
        assertEquals(32, parts[0].length)
        assertEquals(64, parts[1].length)
    }

    @Test
    fun malformedRecordsNeverMatch() {
        assertFalse(AppSettings.settingsPinMatches("", "1234"))
        assertFalse(AppSettings.settingsPinMatches("nocolon", "1234"))
        assertFalse(AppSettings.settingsPinMatches(":abc", "1234"))
        assertFalse(AppSettings.settingsPinMatches("abc:", "1234"))
        assertFalse(AppSettings.settingsPinMatches("abc:def", "1234"))
    }
}
