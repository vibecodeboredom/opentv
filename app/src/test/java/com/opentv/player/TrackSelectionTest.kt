package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 7.3.0: audio track and subtitle selection labels and lookups. */
class TrackSelectionTest {

    @Test
    fun displayNamePrefersStreamLabel() {
        assertEquals("English", TrackSelection.displayName("eng", "English", 0))
        assertEquals("Español", TrackSelection.displayName("spa", "Español", 1))
    }

    @Test
    fun displayNameFallsBackToLanguageName() {
        assertEquals("English", TrackSelection.displayName("eng", null, 0))
        assertEquals("Spanish", TrackSelection.displayName("spa", "  ", 1))
        assertEquals("French", TrackSelection.displayName("fra", null, 2))
    }

    @Test
    fun displayNameFallsBackToTrackNumber() {
        assertEquals("Track 1", TrackSelection.displayName(null, null, 0))
        assertEquals("Track 3", TrackSelection.displayName("xx", null, 2))
    }

    @Test
    fun displayNameTrimsStreamLabel() {
        assertEquals("English", TrackSelection.displayName("eng", "  English  ", 0))
    }

    @Test
    fun audioAndSubtitleLabelsHandleBlankOrUnknownLanguages() {
        assertEquals("Auto", TrackSelection.audioLabel(""))
        assertEquals("Auto", TrackSelection.audioLabel("invalid"))
        assertEquals("Off", TrackSelection.subtitleLabel(""))
        assertEquals("Off", TrackSelection.subtitleLabel("invalid"))
    }

    @Test
    fun languageDisplayNameHandlesTwoLetterCodes() {
        assertEquals("English", TrackSelection.languageDisplayName("en"))
        assertEquals("Spanish", TrackSelection.languageDisplayName("es"))
    }

    @Test
    fun languageDisplayNameHandlesThreeLetterCodes() {
        assertEquals("English", TrackSelection.languageDisplayName("eng"))
        assertEquals("German", TrackSelection.languageDisplayName("deu"))
    }

    @Test
    fun languageDisplayNameRejectsUnknown() {
        assertNull(TrackSelection.languageDisplayName(null))
        assertNull(TrackSelection.languageDisplayName(""))
        assertNull(TrackSelection.languageDisplayName("xx"))
        assertNull(TrackSelection.languageDisplayName("zzz"))
    }

    @Test
    fun audioLabelIsAutoWhenNothingChosen() {
        assertEquals("Auto", TrackSelection.audioLabel(null))
        assertEquals("English", TrackSelection.audioLabel("eng"))
    }

    @Test
    fun subtitleLabelIsOffWhenNothingChosen() {
        assertEquals("Off", TrackSelection.subtitleLabel(null))
        assertEquals("Spanish", TrackSelection.subtitleLabel("spa"))
    }

    @Test
    fun findByLanguageMatchesCaseInsensitively() {
        val choices = listOf(
            TrackSelection.TrackChoice(0, 0, "English", "eng"),
            TrackSelection.TrackChoice(0, 1, "Spanish", "spa")
        )
        assertEquals(choices[1], TrackSelection.findByLanguage(choices, "SPA"))
        assertNull(TrackSelection.findByLanguage(choices, "fra"))
        assertNull(TrackSelection.findByLanguage(choices, null))
    }
}
