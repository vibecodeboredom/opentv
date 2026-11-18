package com.opentv.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Application (6.1.0): bounds Coil's memory cache for low-RAM
 * devices. Coil's default is 25% of the app's memory class; on the
 * 1GB box that is ~32MB of logo bitmaps held indefinitely. 15% keeps
 * scrolling smooth (the visible rows' logos stay cached) while
 * leaving more headroom for the player and the guide.
 *
 * Also warms the playlist + EPG caches at process start
 * (PlaylistRepository.preload), so the guide and browse screens open
 * with the data already parsed instead of loading on open. The
 * warm-up is fire-and-forget on a background scope and single-flight
 * with the screens' own loads, so it never delays startup and never
 * duplicates a parse.
 *
 * 9.2.0: the logo disk cache is explicit now (LogoCache) instead of
 * Coil's implicit default, and the stale-logo refresh waits 10 s
 * after process start so its network fetches never contend with the
 * launch-critical playlist load. Memory pressure releases the big
 * in-memory caches (onTrimMemory) — they rebuild from the SQLite
 * snapshot in milliseconds.
 */
class OpenTvApp : Application(), ImageLoaderFactory {

    /** 7.3.0: background work that must never block startup — the
     * logo refresh runs here and nowhere else. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        PlaylistRepository.preload(this)
        // 7.3.0: refresh stale channel logos in the background (at
        // most 25 per launch) so a provider's new art shows up
        // without a reinstall. 9.2.0: the run waits 10 s after
        // process start — 25 network fetches + decodes at the exact
        // moment the playlist load is racing to the first screen
        // made cold start slower on a 1 GB box for no reason; the
        // refresh is best-effort and nothing reads its results
        // until the guide opens.
        appScope.launch {
            delay(LOGO_REFRESH_START_DELAY_MS)
            LogoRefresh.refreshStaleLogos(this@OpenTvApp)
        }
    }

    /** 9.2.0: on memory pressure (or when backgrounded) release the
     * in-memory playlist + EPG caches. Both rebuild from the SQLite
     * snapshot on the next open, so this costs the user nothing. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (MemoryPressure.shouldReleaseCaches(level)) {
            PlaylistRepository.releaseMemoryCaches()
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            // 9.2.0: explicit logo disk cache (LogoCache) — a
            // dedicated directory with a 16–64 MB bound, so logos
            // survive a restart without re-downloading and the
            // cache can never grow to hundreds of MB on a big stick.
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve(LogoCache.DIRECTORY_NAME))
                    .maxSizeBytes(LogoCache.cacheSizeBytes(cacheDir.usableSpace))
                    .build()
            }
            .build()

    private companion object {
        /** Delay before the stale-logo refresh runs at app start. */
        const val LOGO_REFRESH_START_DELAY_MS = 10_000L
    }
}
