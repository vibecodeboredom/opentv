package com.opentv.player

import android.content.ComponentCallbacks2
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9.2.0: memory-pressure trim decision. */
class MemoryPressureTest {

    @Test
    fun releasesOnCriticalAndBackgroundedLevels() {
        assertTrue(
            MemoryPressure.shouldReleaseCaches(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
            )
        )
        assertTrue(
            MemoryPressure.shouldReleaseCaches(
                ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN
            )
        )
        assertTrue(
            MemoryPressure.shouldReleaseCaches(
                ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
            )
        )
        assertTrue(
            MemoryPressure.shouldReleaseCaches(
                ComponentCallbacks2.TRIM_MEMORY_COMPLETE
            )
        )
    }

    @Test
    fun keepsCachesOnMildRunningLevels() {
        // The caches are still needed while watching; only real
        // pressure (or being backgrounded) releases them.
        assertFalse(
            MemoryPressure.shouldReleaseCaches(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE
            )
        )
        assertFalse(
            MemoryPressure.shouldReleaseCaches(
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW
            )
        )
    }

    // ---- Gemini -------------------------------------------------------

    @Test
    fun extremeLevelReleases() {
        assertTrue(MemoryPressure.shouldReleaseCaches(999))
    }
}
