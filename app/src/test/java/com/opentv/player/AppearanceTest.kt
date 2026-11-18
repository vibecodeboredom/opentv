package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 9.1.0: appearance math — guide row density, font scaling, key parsing. */
class AppearanceTest {

    // ---- Guide row density -------------------------------------------

    @Test
    fun comfortableKeepsTodaysRowHeights() {
        assertEquals(40, Appearance.guideRowHeightDp(40, Appearance.GuideDensity.COMFORTABLE))
        assertEquals(48, Appearance.guideRowHeightDp(48, Appearance.GuideDensity.COMFORTABLE))
        assertEquals(56, Appearance.guideRowHeightDp(56, Appearance.GuideDensity.COMFORTABLE))
    }

    @Test
    fun compactThinsEverySize() {
        // 0.8x, rounded: 40 -> 32, 48 -> 38, 56 -> 45.
        assertEquals(32, Appearance.guideRowHeightDp(40, Appearance.GuideDensity.COMPACT))
        assertEquals(38, Appearance.guideRowHeightDp(48, Appearance.GuideDensity.COMPACT))
        assertEquals(45, Appearance.guideRowHeightDp(56, Appearance.GuideDensity.COMPACT))
    }

    @Test
    fun compactNeverGoesBelowTheMinimum() {
        assertEquals(
            Appearance.MIN_GUIDE_ROW_HEIGHT_DP,
            Appearance.guideRowHeightDp(28, Appearance.GuideDensity.COMPACT)
        )
        assertEquals(
            Appearance.MIN_GUIDE_ROW_HEIGHT_DP,
            Appearance.guideRowHeightDp(20, Appearance.GuideDensity.COMPACT)
        )
    }

    // ---- 9.1.1: font-aware row floor ----------------------------------

    @Test
    fun mediumFontKeepsTheOldFloor() {
        // 15sp * 1.0 * 1.2 = 18dp line + 8dp card padding = 26dp,
        // below the absolute 28dp floor, so the floor stays 28.
        assertEquals(28, Appearance.minGuideRowHeightDp(Appearance.FontSize.MEDIUM))
    }

    @Test
    fun smallFontDoesNotRaiseTheFloor() {
        // 15sp * 0.9 * 1.2 = 16.2dp -> 17 + 8 = 25dp, below the floor.
        assertEquals(28, Appearance.minGuideRowHeightDp(Appearance.FontSize.SMALL))
    }

    @Test
    fun largeFontRaisesTheFloor() {
        // 15sp * 1.15 * 1.2 = 20.7dp -> 21 + 8 = 29dp: a row at the
        // old 28dp floor would clip the Large title's top and bottom.
        assertEquals(29, Appearance.minGuideRowHeightDp(Appearance.FontSize.LARGE))
    }

    @Test
    fun largeFontFloorsAThinRow() {
        // A hypothetical 24dp base thinned to 19dp by Compact still
        // floors at 29dp with Large text, not 28dp.
        assertEquals(
            29,
            Appearance.guideRowHeightDp(
                24, Appearance.GuideDensity.COMPACT, Appearance.FontSize.LARGE
            )
        )
        assertEquals(
            29,
            Appearance.guideRowHeightDp(
                20, Appearance.GuideDensity.COMFORTABLE, Appearance.FontSize.LARGE
            )
        )
    }

    @Test
    fun compactSmallLargeStillFits() {
        // The thinnest real row (40dp * 0.8 = 32dp) is still above
        // the 29dp Large floor, so no real row changes height.
        assertEquals(
            32,
            Appearance.guideRowHeightDp(
                40, Appearance.GuideDensity.COMPACT, Appearance.FontSize.LARGE
            )
        )
    }

    // ---- Gemini-generated (verified by hand) --------------------------

    @Test
    fun comfortableLargeKeepsTheBaseHeight() {
        // The 40dp Comfortable base is above the 29dp Large floor,
        // so Large text does not grow a Comfortable row.
        assertEquals(
            40,
            Appearance.guideRowHeightDp(
                40, Appearance.GuideDensity.COMFORTABLE, Appearance.FontSize.LARGE
            )
        )
    }

    // ---- Font size ----------------------------------------------------

    @Test
    fun mediumKeepsTodaysTextSizes() {
        assertEquals(15f, Appearance.scaledTextSp(15f, Appearance.FontSize.MEDIUM), 0.001f)
        assertEquals(16f, Appearance.scaledTextSp(16f, Appearance.FontSize.MEDIUM), 0.001f)
    }

    @Test
    fun smallShrinksText() {
        assertEquals(13.5f, Appearance.scaledTextSp(15f, Appearance.FontSize.SMALL), 0.001f)
        assertEquals(14.4f, Appearance.scaledTextSp(16f, Appearance.FontSize.SMALL), 0.001f)
    }

    @Test
    fun largeGrowsText() {
        assertEquals(17.25f, Appearance.scaledTextSp(15f, Appearance.FontSize.LARGE), 0.001f)
        assertEquals(18.4f, Appearance.scaledTextSp(16f, Appearance.FontSize.LARGE), 0.001f)
    }

    // ---- Key parsing --------------------------------------------------

    @Test
    fun densityKeysRoundTrip() {
        assertEquals(
            Appearance.GuideDensity.COMPACT,
            Appearance.parseGuideDensity(Appearance.guideDensityKey(Appearance.GuideDensity.COMPACT))
        )
        assertEquals(
            Appearance.GuideDensity.COMFORTABLE,
            Appearance.parseGuideDensity(
                Appearance.guideDensityKey(Appearance.GuideDensity.COMFORTABLE)
            )
        )
    }

    @Test
    fun unknownDensityFallsBackToComfortable() {
        assertEquals(Appearance.GuideDensity.COMFORTABLE, Appearance.parseGuideDensity("roomy"))
        assertEquals(Appearance.GuideDensity.COMFORTABLE, Appearance.parseGuideDensity(null))
        // A backup from before 9.1.0 has no key at all.
        assertEquals(Appearance.GuideDensity.COMFORTABLE, Appearance.parseGuideDensity(""))
    }

    @Test
    fun fontSizeKeysRoundTrip() {
        for (size in Appearance.FontSize.entries) {
            assertEquals(size, Appearance.parseFontSize(Appearance.fontSizeKey(size)))
        }
    }

    @Test
    fun unknownFontSizeFallsBackToMedium() {
        assertEquals(Appearance.FontSize.MEDIUM, Appearance.parseFontSize("huge"))
        assertEquals(Appearance.FontSize.MEDIUM, Appearance.parseFontSize(null))
        assertEquals(Appearance.FontSize.MEDIUM, Appearance.parseFontSize(""))
    }

    // ---- Gemini-generated (verified by hand) --------------------------

    @Test
    fun zeroBaseHeightClampsToMinimum() {
        assertEquals(
            Appearance.MIN_GUIDE_ROW_HEIGHT_DP,
            Appearance.guideRowHeightDp(0, Appearance.GuideDensity.COMFORTABLE)
        )
        assertEquals(
            Appearance.MIN_GUIDE_ROW_HEIGHT_DP,
            Appearance.guideRowHeightDp(0, Appearance.GuideDensity.COMPACT)
        )
    }

    @Test
    fun negativeBaseHeightClampsToMinimum() {
        assertEquals(
            Appearance.MIN_GUIDE_ROW_HEIGHT_DP,
            Appearance.guideRowHeightDp(-10, Appearance.GuideDensity.COMFORTABLE)
        )
    }

    @Test
    fun zeroBaseTextStaysZero() {
        assertEquals(0.0f, Appearance.scaledTextSp(0f, Appearance.FontSize.SMALL), 0.001f)
        assertEquals(0.0f, Appearance.scaledTextSp(0f, Appearance.FontSize.LARGE), 0.001f)
    }

    @Test
    fun densityParsingIsCaseSensitive() {
        // Only the exact lowercase key matches; anything else (wrong
        // case, whitespace, junk) falls back to Comfortable.
        assertEquals(Appearance.GuideDensity.COMFORTABLE, Appearance.parseGuideDensity("COMPACT"))
        assertEquals(Appearance.GuideDensity.COMFORTABLE, Appearance.parseGuideDensity(" compact "))
        assertEquals(Appearance.GuideDensity.COMFORTABLE, Appearance.parseGuideDensity("123"))
    }
}
