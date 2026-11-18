package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.3.1: per-channel track-selection persistence encoding. */
class TrackPrefsTest {

    @Test
    fun keysAreNamespacedPerChannel() {
        assertEquals("a:ch-1", TrackPrefs.audioKey("ch-1"))
        assertEquals("s:ch-1", TrackPrefs.subtitleKey("ch-1"))
        assertEquals("a:other", TrackPrefs.audioKey("other"))
    }

    @Test
    fun encodeAudioKeepsLanguageCodes() {
        assertEquals("eng", TrackPrefs.encodeChoice("eng", TrackPrefs.AUDIO_AUTO))
        assertEquals("spa", TrackPrefs.encodeChoice("spa", TrackPrefs.AUDIO_AUTO))
    }

    @Test
    fun encodeAudioUsesAutoSentinelForNone() {
        assertEquals("auto", TrackPrefs.encodeChoice(null, TrackPrefs.AUDIO_AUTO))
        assertEquals("auto", TrackPrefs.encodeChoice("", TrackPrefs.AUDIO_AUTO))
        assertEquals("auto", TrackPrefs.encodeChoice("   ", TrackPrefs.AUDIO_AUTO))
    }

    @Test
    fun encodeSubtitleUsesOffSentinelForNone() {
        assertEquals("off", TrackPrefs.encodeChoice(null, TrackPrefs.SUBTITLE_OFF))
        assertEquals("off", TrackPrefs.encodeChoice("", TrackPrefs.SUBTITLE_OFF))
    }

    @Test
    fun decodeAudioDistinguishesNeverPickedFromAuto() {
        // No key at all: never picked.
        val (neverLang, neverSet) =
            TrackPrefs.decodeChoice(null, TrackPrefs.AUDIO_AUTO)
        assertNull(neverLang)
        assertFalse(neverSet)
        // Explicit Auto: picked, but no language.
        val (autoLang, autoSet) =
            TrackPrefs.decodeChoice("auto", TrackPrefs.AUDIO_AUTO)
        assertNull(autoLang)
        assertTrue(autoSet)
        // A language pick.
        val (lang, set) =
            TrackPrefs.decodeChoice("eng", TrackPrefs.AUDIO_AUTO)
        assertEquals("eng", lang)
        assertTrue(set)
    }

    @Test
    fun decodeSubtitleDistinguishesNeverPickedFromOff() {
        val (neverLang, neverSet) =
            TrackPrefs.decodeChoice(null, TrackPrefs.SUBTITLE_OFF)
        assertNull(neverLang)
        assertFalse(neverSet)
        val (offLang, offSet) =
            TrackPrefs.decodeChoice("off", TrackPrefs.SUBTITLE_OFF)
        assertNull(offLang)
        assertTrue(offSet)
        val (lang, set) =
            TrackPrefs.decodeChoice("spa", TrackPrefs.SUBTITLE_OFF)
        assertEquals("spa", lang)
        assertTrue(set)
    }

    @Test
    fun encodeDecodeRoundTrip() {
        for (language in listOf<String?>(null, "eng", "fra", "")) {
            val encoded = TrackPrefs.encodeChoice(language, TrackPrefs.AUDIO_AUTO)
            val (decoded, set) = TrackPrefs.decodeChoice(encoded, TrackPrefs.AUDIO_AUTO)
            assertTrue(set)
            assertEquals(language?.takeIf { it.isNotBlank() }, decoded)
        }
    }
}
