package com.opentv.player

/**
 * 9.2.0 — Performance: guide scroll tuning for large playlists.
 *
 * Rows used to append in 24-row chunks — roughly 200 views built
 * synchronously on the main thread every time focus neared the end
 * of what was built, a visible hitch on a slow box. Smaller chunks
 * spread the same work out; a bigger focus buffer starts each chunk
 * earlier, so the smaller chunks still stay ahead of the user.
 * The first paint is unchanged (12 rows synchronously, the rest of
 * the initial 24 on the next frame).
 */
object GuideScrollTuning {

    /** Rows built when the guide first opens (enough for ~3 screens). */
    const val INITIAL_ROWS = 24

    /** Rows built synchronously for the first paint (~2 screens). */
    const val FIRST_PAINT_ROWS = 12

    /** Rows appended per lazy chunk as the user scrolls (was 24). */
    const val APPEND_ROWS = 8

    /** Focus reaching this close to the last built row triggers the
     * next chunk (was 6). Kept larger than APPEND_ROWS so an append
     * is always triggered before the user can outrun the built rows. */
    const val FOCUS_BUFFER = 12

    /** True when focus at [focusRowIndex] is close enough to the last
     * built row ([builtRowCount]) that the next chunk should be built.
     * A negative focus row or an empty grid never triggers an append. */
    fun shouldAppend(builtRowCount: Int, focusRowIndex: Int): Boolean {
        if (focusRowIndex < 0 || builtRowCount <= 0) return false
        return focusRowIndex >= builtRowCount - FOCUS_BUFFER
    }
}
