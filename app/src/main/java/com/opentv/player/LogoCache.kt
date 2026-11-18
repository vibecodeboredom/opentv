package com.opentv.player

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
}
