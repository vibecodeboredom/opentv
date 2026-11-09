package com.opentv.player

import android.widget.ImageView
import coil.load

/**
 * Logo loading, sized (6.1.0): every channel logo in the app is shown
 * at 20–96dp, but the plain `load(url)` calls used to decode the
 * provider's full-resolution image (often 400–1000px) and keep it in
 * the memory cache. On a 1GB box that is the difference between a
 * smooth scroll and an out-of-memory kill. loadLogo downsamples to
 * the size actually displayed, so a 32dp sidebar logo costs a
 * 64–96px bitmap instead of a 1000px one.
 *
 * Top-level extensions (same package) so call sites need no import.
 */

/** Load a logo downsampled to [sizeDp] (square target, in dp). */
fun ImageView.loadLogo(
    url: String?,
    sizeDp: Int,
    placeholderRes: Int = R.drawable.ic_play
) {
    val px = (sizeDp * resources.displayMetrics.density).toInt().coerceAtLeast(1)
    load(url) {
        size(px)
        placeholder(placeholderRes)
        error(placeholderRes)
    }
}

/** Load a logo downsampled to an exact pixel size (Leanback cards). */
fun ImageView.loadLogo(
    url: String?,
    widthPx: Int,
    heightPx: Int,
    placeholderRes: Int = R.drawable.ic_play
) {
    load(url) {
        size(widthPx, heightPx)
        placeholder(placeholderRes)
        error(placeholderRes)
    }
}
