package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9.2.0: player buffer budget — fast start, bounded max buffer. */
class PlaybackBuffersTest {

    @Test
    fun maxBufferIsTighterThanBefore() {
        // Was 30 s; 20 s at a typical 6 Mbps live stream is ~7 MB
        // less held in the sample queue on long sessions.
        assertEquals(20_000, PlaybackBuffers.MAX_BUFFER_MS)
    }

    @Test
    fun startupAndRebufferBehaviourUnchanged() {
        assertEquals(10_000, PlaybackBuffers.MIN_BUFFER_MS)
        assertEquals(500, PlaybackBuffers.BUFFER_FOR_PLAYBACK_MS)
        assertEquals(1_500, PlaybackBuffers.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS)
    }

    @Test
    fun bufferOrderingIsValid() {
        assertTrue(PlaybackBuffers.MAX_BUFFER_MS > PlaybackBuffers.MIN_BUFFER_MS)
        assertTrue(PlaybackBuffers.BUFFER_FOR_PLAYBACK_MS <= PlaybackBuffers.MIN_BUFFER_MS)
        assertTrue(
            PlaybackBuffers.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS <=
                PlaybackBuffers.MIN_BUFFER_MS
        )
        assertTrue(PlaybackBuffers.BUFFER_FOR_PLAYBACK_MS > 0)
    }
}
