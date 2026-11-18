package com.opentv.player

import android.content.Context
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity

/**
 * 9.0.0 (Nova): programmatic versions of the accent-coloured
 * drawables that used to exist only as XML with the old green
 * hard-coded (guide_cell_now_bg, guide_menu_item_bg,
 * guide_progress_drawable, card_accent_bg). The shapes, radii
 * and non-accent colours are identical to those XML files; the
 * accent itself comes from [AppSettings], so the Settings
 * accent choice drives every one of these surfaces from one
 * place. The XML files remain as the layouts' static defaults
 * (and are what a surface shows before code re-points it).
 */
object AccentDrawables {

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private fun roundedRect(context: Context, color: Int, radiusDp: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = dp(context, radiusDp).toFloat()
        }

    /** The guide's "now" cell: accent fill, darker accent when
     * pressed, white when focused (the grid's focus language). */
    fun guideCellNowBackground(context: Context): StateListDrawable {
        val out = StateListDrawable()
        out.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(context, 0xFFFFFFFF.toInt(), 8)
        )
        out.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(context, AppSettings.accentColorDark(context), 8)
        )
        out.addState(
            intArrayOf(),
            roundedRect(context, AppSettings.accentColor(context), 8)
        )
        return out
    }

    /** A focusable menu row (guide menu, recent searches):
     * accent when focused, panel grey when pressed, transparent
     * otherwise. */
    fun menuItemBackground(context: Context): StateListDrawable {
        val out = StateListDrawable()
        out.addState(
            intArrayOf(android.R.attr.state_focused),
            roundedRect(context, AppSettings.accentColor(context), 8)
        )
        out.addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedRect(context, 0xFF333639.toInt(), 8)
        )
        out.addState(
            intArrayOf(),
            roundedRect(context, 0x00000000, 8)
        )
        return out
    }

    /** Horizontal progress bar: dark track, accent fill — the
     * guide details header, the guide now-playing bar and the
     * playback info overlay all share this look. The layer ids
     * are the framework's, so ProgressBar drives the clip level
     * exactly as it did with the XML version. */
    fun progressDrawable(context: Context): LayerDrawable {
        val track = roundedRect(context, 0xFF333639.toInt(), 4)
        val fill = ClipDrawable(
            roundedRect(context, AppSettings.accentColor(context), 4),
            Gravity.LEFT,
            ClipDrawable.HORIZONTAL
        )
        val out = LayerDrawable(arrayOf(track, fill))
        out.setId(0, android.R.id.background)
        out.setId(1, android.R.id.progress)
        return out
    }

    /** The mobile home "Continue watching" card: dark fill with
     * a 2dp accent outline. */
    fun cardOutlineBackground(context: Context): GradientDrawable =
        roundedRect(context, 0xFF161616.toInt(), 16).apply {
            setStroke(dp(context, 2), AppSettings.accentColor(context))
        }
}
