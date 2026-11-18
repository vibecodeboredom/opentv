package com.opentv.player

import android.content.ComponentCallbacks2

/**
 * 9.2.0 — Performance: memory pressure.
 *
 * The in-memory playlist + EPG caches are the app's biggest
 * long-lived allocations after the player itself — the whole guide
 * map sits in RAM for the 12 h EPG TTL. When the system is under
 * memory pressure, or the app is backgrounded, those caches are
 * released: both rebuild from the SQLite snapshot in milliseconds
 * on the next open, so releasing them costs nothing the user can
 * feel. Coil trims its own memory cache on the same signal.
 */
object MemoryPressure {

    /** True when a trim level means "release the big in-memory
     * caches". TRIM_MEMORY_RUNNING_CRITICAL (15) is the threshold:
     * it covers the foreground-critical case and, numerically, every
     * backgrounded level above it (UI_HIDDEN 20, BACKGROUND 40,
     * MODERATE 60, COMPLETE 80). The milder running levels (5, 10)
     * do not release — the caches are still needed while watching. */
    fun shouldReleaseCaches(level: Int): Boolean =
        level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL
}
