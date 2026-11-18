package com.opentv.player

import android.speech.tts.TextToSpeech
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/** 9.3.0: accessibility & remote — larger-text math, the held-OK
 * action parsing, and the spoken-name utterance text. */
class AccessibilityTest {

    // ---- Larger-text mode (Appearance) --------------------------------

    @Test
    fun largerTextOffLeavesTheScaleAlone() {
        assertEquals(0.9f, Appearance.effectiveFontScale(Appearance.FontSize.SMALL, false), 0.001f)
        assertEquals(1.0f, Appearance.effectiveFontScale(Appearance.FontSize.MEDIUM, false), 0.001f)
        assertEquals(1.15f, Appearance.effectiveFontScale(Appearance.FontSize.LARGE, false), 0.001f)
    }

    @Test
    fun largerTextAddsQuarterOnTopOfTheFontSize() {
        // 0.9 * 1.25, 1.0 * 1.25, 1.15 * 1.25.
        assertEquals(1.125f, Appearance.effectiveFontScale(Appearance.FontSize.SMALL, true), 0.001f)
        assertEquals(1.25f, Appearance.effectiveFontScale(Appearance.FontSize.MEDIUM, true), 0.001f)
        assertEquals(1.4375f, Appearance.effectiveFontScale(Appearance.FontSize.LARGE, true), 0.001f)
    }

    @Test
    fun scaledTextUsesTheEffectiveScale() {
        assertEquals(15f, Appearance.scaledTextSp(15f, Appearance.FontSize.MEDIUM, false), 0.001f)
        assertEquals(18.75f, Appearance.scaledTextSp(15f, Appearance.FontSize.MEDIUM, true), 0.001f)
        // Large + larger text is the biggest the guide gets.
        assertEquals(21.5625f, Appearance.scaledTextSp(15f, Appearance.FontSize.LARGE, true), 0.001f)
        // Small + larger text lands between Medium and Large.
        assertEquals(16.875f, Appearance.scaledTextSp(15f, Appearance.FontSize.SMALL, true), 0.001f)
    }

    @Test
    fun rowFloorGrowsWithLargerText() {
        // Medium: 15 * 1.0 * 1.2 = 18 -> 18 + 8 = 26, floored to 28.
        assertEquals(28, Appearance.minGuideRowHeightDp(Appearance.FontSize.MEDIUM, false))
        // Medium + larger: 15 * 1.25 * 1.2 = 22.5 -> ceil 23 + 8 = 31.
        assertEquals(31, Appearance.minGuideRowHeightDp(Appearance.FontSize.MEDIUM, true))
        // Large + larger: 15 * 1.4375 * 1.2 = 25.875 -> ceil 26 + 8 = 34.
        assertEquals(34, Appearance.minGuideRowHeightDp(Appearance.FontSize.LARGE, true))
    }

    @Test
    fun compactRowNeverClipsALargerTitle() {
        // Compact 40dp at Large + larger text: the 0.8x row (32dp)
        // would clip the title, so the floor (34dp) wins.
        assertEquals(
            34,
            Appearance.guideRowHeightDp(
                40, Appearance.GuideDensity.COMPACT,
                Appearance.FontSize.LARGE, true
            )
        )
        // Comfortable keeps today's height even with larger text.
        assertEquals(
            40,
            Appearance.guideRowHeightDp(
                40, Appearance.GuideDensity.COMFORTABLE,
                Appearance.FontSize.MEDIUM, true
            )
        )
    }

    // ---- Held-OK action (AppSettings) ----------------------------------

    @Test
    fun longPressOkActionParsesEveryKey() {
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction("menu")
        )
        assertEquals(
            AppSettings.LongPressOkAction.GUIDE,
            AppSettings.parseLongPressOkAction("guide")
        )
        assertEquals(
            AppSettings.LongPressOkAction.NOTHING,
            AppSettings.parseLongPressOkAction("nothing")
        )
    }

    @Test
    fun longPressOkActionDefaultsToMenu() {
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction(null)
        )
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction("bogus")
        )
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction("")
        )
    }

    @Test
    fun longPressOkActionKeysRoundTrip() {
        for (action in AppSettings.LongPressOkAction.values()) {
            assertEquals(
                action,
                AppSettings.parseLongPressOkAction(
                    AppSettings.longPressOkActionKey(action)
                )
            )
        }
    }

    // ---- Spoken channel names (ChannelAnnouncer) -----------------------

    @Test
    fun utteranceIsJustTheChannelName() {
        assertEquals("CNN", ChannelAnnouncer.utteranceFor("CNN"))
        assertEquals("BBC News", ChannelAnnouncer.utteranceFor("BBC News"))
    }

    @Test
    fun utteranceKeepsAnEmptyNameEmpty() {
        assertTrue(ChannelAnnouncer.utteranceFor("").isBlank())
    }

    @Test
    fun utteranceKeepsTheNameVerbatim() {
        // No prefix, no "now playing" — the info overlay already
        // shows the rest, and a long sentence on every zap would
        // talk over the channel itself.
        assertFalse(ChannelAnnouncer.utteranceFor("CNN").contains("playing"))
        assertEquals("CNN", ChannelAnnouncer.utteranceFor("CNN"))
    }

    // ---- Extra tests from Gemini lite (verified by hand) -----------

    @Test
    fun minGuideRowHeightDpEdgeCases() {
        // Small font size without larger text
        assertEquals(28, Appearance.minGuideRowHeightDp(Appearance.FontSize.SMALL, false))
        // Small font size with larger text (0.9 * 1.25 = 1.125 * 15 * 1.2 = 20.25 -> ceil 21 + 8 = 29)
        assertEquals(29, Appearance.minGuideRowHeightDp(Appearance.FontSize.SMALL, true))
        // Large font size without larger text (1.15 * 15 * 1.2 = 20.7 -> ceil 21 + 8 = 29)
        assertEquals(29, Appearance.minGuideRowHeightDp(Appearance.FontSize.LARGE, false))
    }

    @Test
    fun guideRowHeightDpFourArgOverload() {
        // Comfortable density scales base height directly when above floor
        assertEquals(48, Appearance.guideRowHeightDp(48, Appearance.GuideDensity.COMFORTABLE, Appearance.FontSize.MEDIUM, false))
        // Compact density scales base height (48 * 0.8 = 38.4 -> round 38), which is above the 28 floor
        assertEquals(38, Appearance.guideRowHeightDp(48, Appearance.GuideDensity.COMPACT, Appearance.FontSize.MEDIUM, false))
    }

    @Test
    fun scaledTextSpNegativeBaseStaysNegative() {
        assertEquals(-11.25f, Appearance.scaledTextSp(-10f, Appearance.FontSize.SMALL, true), 0.001f)
    }

    @Test
    fun utteranceForWhitespaceAndSpecialCharacters() {
        assertEquals("   ", ChannelAnnouncer.utteranceFor("   "))
        assertEquals("Channel 123!@#", ChannelAnnouncer.utteranceFor("Channel 123!@#"))
    }

    // ---- 9.3.1: TTS language fallback (ChannelAnnouncer) -------------

    @Test
    fun pickLocalePrefersTheFullDeviceLocale() {
        assertEquals(
            Locale("en", "US"),
            ChannelAnnouncer.pickLocale(Locale("en", "US")) {
                TextToSpeech.LANG_AVAILABLE
            }
        )
    }

    @Test
    fun pickLocaleFallsBackToTheBareLanguage() {
        // Engine has "en" data but no "en_US" voice: speak English
        // instead of falling through to the engine default.
        assertEquals(
            Locale("en"),
            ChannelAnnouncer.pickLocale(Locale("en", "US")) { locale ->
                if (locale == Locale("en", "US")) TextToSpeech.LANG_NOT_SUPPORTED
                else TextToSpeech.LANG_AVAILABLE
            }
        )
    }

    @Test
    fun pickLocaleReturnsNullWhenNeitherIsAvailable() {
        assertEquals(
            null,
            ChannelAnnouncer.pickLocale(Locale("en", "US")) {
                TextToSpeech.LANG_NOT_SUPPORTED
            }
        )
    }

    @Test
    fun pickLocaleHandlesALanguageOnlyDefault() {
        assertEquals(
            Locale("es"),
            ChannelAnnouncer.pickLocale(Locale("es")) {
                TextToSpeech.LANG_COUNTRY_AVAILABLE
            }
        )
    }

    // ---- 9.3.1: channel-number column width (Appearance) -------------

    @Test
    fun numberColumnWidthIsTheBaseAtMedium() {
        assertEquals(
            40,
            Appearance.numberColumnWidthDp(40, Appearance.FontSize.MEDIUM, false)
        )
        assertEquals(
            28,
            Appearance.numberColumnWidthDp(28, Appearance.FontSize.MEDIUM, false)
        )
    }

    @Test
    fun numberColumnWidthGrowsWithTheFontScale() {
        // Large: 40 * 1.15 = 46; Large + larger: 40 * 1.4375 = 57.5 -> 58.
        assertEquals(
            46,
            Appearance.numberColumnWidthDp(40, Appearance.FontSize.LARGE, false)
        )
        assertEquals(
            58,
            Appearance.numberColumnWidthDp(40, Appearance.FontSize.LARGE, true)
        )
        // Sidebar Small base: 28 * 1.4375 = 40.25 -> 40.
        assertEquals(
            40,
            Appearance.numberColumnWidthDp(28, Appearance.FontSize.LARGE, true)
        )
        // Medium + larger: 28 * 1.25 = 35.
        assertEquals(
            35,
            Appearance.numberColumnWidthDp(28, Appearance.FontSize.MEDIUM, true)
        )
    }

    @Test
    fun numberColumnWidthNeverShrinksBelowTheBase() {
        // Small scales down (0.9x) but the column keeps today's width.
        assertEquals(
            40,
            Appearance.numberColumnWidthDp(40, Appearance.FontSize.SMALL, false)
        )
        assertEquals(
            28,
            Appearance.numberColumnWidthDp(28, Appearance.FontSize.SMALL, false)
        )
    }

    // ---- Extra tests from Gemini lite (verified by hand) -----------

    @Test
    fun numberColumnWidthScalesForEverySizeWithLargerText() {
        val base = 100
        // SMALL 0.9 * 1.25 = 1.125 -> 112.5 -> 113.
        assertEquals(
            113,
            Appearance.numberColumnWidthDp(
                base, Appearance.FontSize.SMALL, true
            )
        )
        // MEDIUM 1.0 * 1.25 = 1.25 -> 125.
        assertEquals(
            125,
            Appearance.numberColumnWidthDp(
                base, Appearance.FontSize.MEDIUM, true
            )
        )
        // LARGE 1.15 * 1.25 = 1.4375 -> 143.75 -> 144.
        assertEquals(
            144,
            Appearance.numberColumnWidthDp(
                base, Appearance.FontSize.LARGE, true
            )
        )
    }

    @Test
    fun numberColumnWidthOfZeroStaysZero() {
        assertEquals(
            0,
            Appearance.numberColumnWidthDp(
                0, Appearance.FontSize.MEDIUM, false
            )
        )
    }
}
