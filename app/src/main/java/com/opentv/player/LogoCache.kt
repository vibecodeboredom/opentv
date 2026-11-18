package com.opentv.player

import java.io.File

/**
 * 9.2.0 — Performance: the channel-logo disk cache, made explicit.
 *
 * Logos used to rely on Coil's implicit default disk cache: 2% of
 * the whole disk (hundreds of MB on a big stick) in cacheDir, which
 * is also the first thing the OS purges when storage runs low — so
 * logos re-downloaded after every squeeze, and nothing bounded the
 * growth. This sizes a dedicated cache: 2% of the usable space,
 * clamped to 16–64 MB. Logos are downsampled to at most 96px
 * (~10–30 KB each), so 64 MB holds thousands of them; 16 MB is
 * still hundreds on a nearly-full 8 GB box.
 */
object LogoCache {

    /** Directory under cacheDir holding the logo disk cache. */
    const val DIRECTORY_NAME = "logo_cache"

    /** Smallest cache worth having (a nearly-full small box). */
    const val MIN_CACHE_BYTES = 16L * 1024 * 1024

    /** Largest cache worth having (thousands of downsampled logos). */
    const val MAX_CACHE_BYTES = 64L * 1024 * 1024

    /** Share of the usable space the cache may take. */
    const val SIZE_PERCENT = 2

    /** The disk-cache size for a partition with [usableSpaceBytes]
     * usable: 2% of it, clamped to 16–64 MB. A non-positive reading
     * (statfs failed) falls back to the minimum instead of 0. */
    fun cacheSizeBytes(usableSpaceBytes: Long): Long {
        if (usableSpaceBytes <= 0) return MIN_CACHE_BYTES
        val twoPercent = usableSpaceBytes * SIZE_PERCENT / 100
        return twoPercent.coerceIn(MIN_CACHE_BYTES, MAX_CACHE_BYTES)
    }

    /**
     * 9.2.1 — Coil 2's implicit default disk-cache directory
     * (pre-9.2.0). Before 9.2.0 the loader used Coil's default, which
     * lives in `image_cache` with a 2%-of-disk cap — hundreds of MB
     * on a big stick. 9.2.0 moved the cache to [DIRECTORY_NAME] and
     * nothing ever reads, trims or deletes the old directory again,
     * so it sits there as dead weight on every device that ran an
     * older build. The old entries are logos only (nothing else in
     * this app used Coil's disk cache), so deleting the directory
     * just means those logos re-download once on demand.
     */
    const val LEGACY_DIRECTORY_NAME = "image_cache"

    /** Delete the legacy [LEGACY_DIRECTORY_NAME] under [cacheDir].
     * Returns true when a legacy directory was removed, false when
     * there was nothing to delete (or the delete failed). The
     * current [DIRECTORY_NAME] cache is never touched. */
    fun deleteLegacyCache(cacheDir: File): Boolean {
        val legacy = File(cacheDir, LEGACY_DIRECTORY_NAME)
        if (!legacy.exists()) return false
        return legacy.deleteRecursively()
    }
}
