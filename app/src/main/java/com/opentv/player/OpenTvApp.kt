package com.opentv.player

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.memory.MemoryCache

/**
 * Application (6.1.0): bounds Coil's memory cache for low-RAM
 * devices. Coil's default is 25% of the app's memory class; on the
 * 1GB box that is ~32MB of logo bitmaps held indefinitely. 15% keeps
 * scrolling smooth (the visible rows' logos stay cached) while
 * leaving more headroom for the player and the guide.
 */
class OpenTvApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .build()
}
