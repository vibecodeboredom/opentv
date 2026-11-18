package com.opentv.player

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 9.1.0 — Appearance. Pure (no Context, no Android framework) math
 * for the appearance settings, so it is unit-testable on the JVM:
 *
 * - Guide row density: how tall the guide rows are, on top of the
 *   channel-list size (which already sets the base row height —
 *   40/48/56dp for Small/Medium/Large). Comfortable keeps today's
 *   heights (scale 1.0, the default); Compact thins them to 0.8x so
 *   more channels fit on screen.
 * - Font size: a scale applied to the guide + sidebar text (programme
 *   titles, channel names/numbers, header labels, sidebar rows).
 *   Medium is today's size (scale 1.0, the default).
 *
 * The high-contrast focus style and the reduced-animations toggle
 * are plain booleans in AppSettings — they pick drawables / skip
 * animations at the call site, so they need no math here.
 *
 * 9.3.0 — larger-text mode: a toggle (on top of the font-size
 * setting) that makes the guide + sidebar text larger still, for
 * readability from the couch. It is a multiplier on the font-size
 * scale, so Small + larger text lands between Medium and Large,
 * and Large + larger text is the biggest the guide gets. The
 * row-height floor grows with it, so a larger title never clips.
 */
object Appearance {

    /** Guide row density. COMFORTABLE is today's row height. */
    enum class GuideDensity(val rowHeightScale: Float) {
        COMPACT(0.8f),
        COMFORTABLE(1.0f)
    }

    /** Font size for the guide + sidebar text. */
    enum class FontSize(val scale: Float) {
        SMALL(0.9f),
        MEDIUM(1.0f),
        LARGE(1.15f)
    }

    /** Extra multiplier when larger-text mode (9.3.0) is on. */
    const val LARGER_TEXT_SCALE = 1.25f

    /** The effective text scale for a font size with larger-text
     * mode on or off. */
    fun effectiveFontScale(fontSize: FontSize, largerText: Boolean): Float =
        fontSize.scale * if (largerText) LARGER_TEXT_SCALE else 1f

    /** Thinnest a guide row is allowed to get (dp) — below this the
     * single-line programme title would clip vertically. 9.1.1: the
     * floor is font-aware — a Large title needs more room than a
     * Medium one (see [minGuideRowHeightDp]), so the absolute floor
     * is only the starting point. */
    const val MIN_GUIDE_ROW_HEIGHT_DP = 28

    /** Programme title size (sp) in a guide cell, before the font
     * scale — the text the row height has to fit. */
    const val PROGRAMME_TITLE_SP = 15f

    /** Safe line-height estimate for the bold programme title
     * (Roboto's real line height is ~1.17x; 1.2x leaves slack). */
    const val TITLE_LINE_HEIGHT_FACTOR = 1.2f

    /** Vertical padding inside a programme card (dp, top + bottom)
     * — the title's line box sits inside this, so the row has to
     * clear both. */
    const val CARD_VERTICAL_PADDING_DP = 8

    /** The thinnest a guide row may be for a font size: the scaled
     * programme title's line box plus the card's vertical padding,
     * never below the absolute floor. At Medium that is 28dp (the
     * old floor); at Large it is 29dp — a Compact row that ignored
     * the font size would clip the title's top and bottom. */
    fun minGuideRowHeightDp(fontSize: FontSize): Int =
        minGuideRowHeightDp(fontSize.scale)

    /** 9.3.0 — the same floor with larger-text mode on or off: the
     * title's line box grows with the effective scale (Large +
     * larger text needs 34dp), so a Compact row can never clip a
     * larger title either. */
    fun minGuideRowHeightDp(fontSize: FontSize, largerText: Boolean): Int =
        minGuideRowHeightDp(effectiveFontScale(fontSize, largerText))

    private fun minGuideRowHeightDp(scale: Float): Int = max(
        MIN_GUIDE_ROW_HEIGHT_DP,
        ceil(PROGRAMME_TITLE_SP * scale * TITLE_LINE_HEIGHT_FACTOR).toInt() +
            CARD_VERTICAL_PADDING_DP
    )

    /** The guide row height for a channel-list-size base height and a
     * density. Compact thins the row; comfortable leaves it alone.
     * Never below the font-aware floor. */
    fun guideRowHeightDp(
        baseHeightDp: Int,
        density: GuideDensity,
        fontSize: FontSize
    ): Int = max(
        minGuideRowHeightDp(fontSize),
        (baseHeightDp * density.rowHeightScale).roundToInt()
    )

    /** 9.3.0 — the same row height with larger-text mode on or off. */
    fun guideRowHeightDp(
        baseHeightDp: Int,
        density: GuideDensity,
        fontSize: FontSize,
        largerText: Boolean
    ): Int = max(
        minGuideRowHeightDp(fontSize, largerText),
        (baseHeightDp * density.rowHeightScale).roundToInt()
    )

    /** The guide row height for a channel-list-size base height and a
     * density, at the default (Medium) font size. */
    fun guideRowHeightDp(baseHeightDp: Int, density: GuideDensity): Int =
        guideRowHeightDp(baseHeightDp, density, FontSize.MEDIUM)

    /** A text size (sp) scaled by the font-size setting. */
    fun scaledTextSp(baseSp: Float, fontSize: FontSize): Float =
        baseSp * fontSize.scale

    /** 9.3.0 — a text size scaled by the font-size setting and the
     * larger-text mode. */
    fun scaledTextSp(baseSp: Float, fontSize: FontSize, largerText: Boolean): Float =
        baseSp * effectiveFontScale(fontSize, largerText)

    /** Parse a stored density key; anything unknown (or null, from an
     * older install) is Comfortable — today's rows. */
    fun parseGuideDensity(raw: String?): GuideDensity = when (raw) {
        "compact" -> GuideDensity.COMPACT
        else -> GuideDensity.COMFORTABLE
    }

    /** Parse a stored font-size key; anything unknown (or null) is
     * Medium — today's text size. */
    fun parseFontSize(raw: String?): FontSize = when (raw) {
        "small" -> FontSize.SMALL
        "large" -> FontSize.LARGE
        else -> FontSize.MEDIUM
    }

    /** The stored key for a density (round-trips through
     * [parseGuideDensity]). */
    fun guideDensityKey(density: GuideDensity): String = when (density) {
        GuideDensity.COMPACT -> "compact"
        GuideDensity.COMFORTABLE -> "comfortable"
    }

    /** The stored key for a font size (round-trips through
     * [parseFontSize]). */
    fun fontSizeKey(fontSize: FontSize): String = when (fontSize) {
        FontSize.SMALL -> "small"
        FontSize.MEDIUM -> "medium"
        FontSize.LARGE -> "large"
    }
}
