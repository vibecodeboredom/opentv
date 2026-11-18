package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 9.2.0: logo disk-cache sizing — 2% of usable space, 16–64 MB. */
class LogoCacheTest {

    @Test
    fun twoPercentOfATypicalStick() {
        // 8 GB usable -> 2% is ~160 MB, clamped to the 64 MB max.
        assertEquals(LogoCache.MAX_CACHE_BYTES, LogoCache.cacheSizeBytes(8L * 1024 * 1024 * 1024))
        // 1 GiB usable -> 2% is 21,474,836 bytes, inside the clamp.
        assertEquals(21_474_836L, LogoCache.cacheSizeBytes(1L * 1024 * 1024 * 1024))
    }

    @Test
    fun nearlyFullSmallBoxGetsTheMinimum() {
        // 100 MB usable -> 2% is 2 MB, raised to the 16 MB floor.
        assertEquals(LogoCache.MIN_CACHE_BYTES, LogoCache.cacheSizeBytes(100L * 1024 * 1024))
    }

    @Test
    fun failedStatfsFallsBackToMinimum() {
        assertEquals(LogoCache.MIN_CACHE_BYTES, LogoCache.cacheSizeBytes(0))
        assertEquals(LogoCache.MIN_CACHE_BYTES, LogoCache.cacheSizeBytes(-1))
    }

    @Test
    fun clampBoundsAreSane() {
        assertTrue(LogoCache.MIN_CACHE_BYTES < LogoCache.MAX_CACHE_BYTES)
        assertEquals(16L * 1024 * 1024, LogoCache.MIN_CACHE_BYTES)
        assertEquals(64L * 1024 * 1024, LogoCache.MAX_CACHE_BYTES)
    }

    // ---- Gemini: exact clamp boundaries -------------------------------

    @Test
    fun exactMaxBoundaryStaysAtMax() {
        // 3.2 GB usable -> 2% is exactly 64 MB.
        val bytesForMax = 64L * 1024 * 1024 * 100 / 2
        assertEquals(LogoCache.MAX_CACHE_BYTES, LogoCache.cacheSizeBytes(bytesForMax))
    }

    @Test
    fun aboveMaxClampsToMax() {
        assertEquals(
            LogoCache.MAX_CACHE_BYTES,
            LogoCache.cacheSizeBytes(10L * 1024 * 1024 * 1024)
        )
    }

    @Test
    fun exactMinBoundaryStaysAtMin() {
        // 800 MB usable -> 2% is exactly 16 MB.
        val bytesForMin = 16L * 1024 * 1024 * 100 / 2
        assertEquals(LogoCache.MIN_CACHE_BYTES, LogoCache.cacheSizeBytes(bytesForMin))
    }

    // ---- 9.2.1: legacy image_cache cleanup ---------------------------

    @Test
    fun deleteLegacyCacheRemovesOldDirAndLeavesCurrentCacheAlone() {
        val dir = createTempDir("opentv-cache")
        try {
            val legacy = File(dir, LogoCache.LEGACY_DIRECTORY_NAME).apply { mkdirs() }
            File(legacy, "dead-entry").writeText("stale logo")
            val current = File(dir, LogoCache.DIRECTORY_NAME).apply { mkdirs() }
            File(current, "keep").writeText("live logo")
            assertTrue(LogoCache.deleteLegacyCache(dir))
            assertFalse(legacy.exists())
            assertTrue(File(current, "keep").exists())
            // Second call: nothing left to delete.
            assertFalse(LogoCache.deleteLegacyCache(dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun deleteLegacyCacheIsNoopWithoutLegacyDir() {
        val dir = createTempDir("opentv-cache")
        try {
            assertFalse(LogoCache.deleteLegacyCache(dir))
        } finally {
            dir.deleteRecursively()
        }
    }
}
