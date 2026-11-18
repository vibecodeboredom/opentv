package com.opentv.player

/**
 * 9.2.0 — Performance: the player's buffer budget.
 *
 * The min buffer stays 10 s, so a slow stream does not rebuffer
 * right after starting, and start-up stays fast (500 ms to first
 * frame). The max buffer drops from 30 s to 20 s — how far ahead a
 * live stream buffers. At a typical 6 Mbps live stream that is
 * roughly 7 MB less held in the sample queue on long viewing
 * sessions, with no change to start-up or rebuffer behaviour.
 */
object PlaybackBuffers {

    /** Buffer before playback may start after a rebuffer. */
    const val MIN_BUFFER_MS = 10_000

    /** Cap on how far ahead a live stream buffers. */
    const val MAX_BUFFER_MS = 20_000

    /** Buffer before the first frame on a fresh start. */
    const val BUFFER_FOR_PLAYBACK_MS = 500

    /** Buffer before resuming after a rebuffer. */
    const val BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS = 1_500
}
