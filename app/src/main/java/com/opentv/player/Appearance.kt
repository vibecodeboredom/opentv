package com.opentv.player

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

    /** Thinnest a guide row is allowed to get (dp) — below this the
     * single-line programme title would clip vertically. */
    const val MIN_GUIDE_ROW_HEIGHT_DP = 28

    /** The guide row height for a channel-list-size base height and a
     * density. Compact thins the row; comfortable leaves it alone. */
    fun guideRowHeightDp(baseHeightDp: Int, density: GuideDensity): Int =
        max(MIN_GUIDE_ROW_HEIGHT_DP, (baseHeightDp * density.rowHeightScale).roundToInt())

    /** A text size (sp) scaled by the font-size setting. */
    fun scaledTextSp(baseSp: Float, fontSize: FontSize): Float =
        baseSp * fontSize.scale

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
