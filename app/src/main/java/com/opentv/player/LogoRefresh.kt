package com.opentv.player

import android.content.Context
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult

/**
 * 7.3.0 — Audio & picture: channel-logo cache refresh. Logos used
 * to go stale until the app was reinstalled — Coil's disk cache
 * has no expiry of its own, so a provider's new logo never
 * replaced the old one. A background refresh re-fetches any logo
 * older than the TTL and overwrites its disk-cache entry, so the
 * new art shows up without the user doing anything.
 */
object LogoRefresh {

    /** A logo is due for a refresh when it has not been refreshed
     * in this long. */
    const val LOGO_TTL_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** At most this many logos are refreshed per app launch. The
     * first launch after install has every logo "never refreshed",
     * so an uncapped run would fire hundreds of fetches at once;
     * the cap bounds the burst and the backlog drains over the
     * next launches. */
    const val MAX_REFRESH_PER_RUN = 25

    private const val PREFS_NAME = "logo_refresh"

    /** True when a logo last refreshed at [lastRefreshMillis] is due
     * for a refresh at [nowMillis]. Never refreshed (null) is stale;
     * a future timestamp (the clock moved) is not. */
    fun isStale(lastRefreshMillis: Long?, nowMillis: Long): Boolean {
        if (lastRefreshMillis == null) return true
        if (lastRefreshMillis > nowMillis) return false
        return nowMillis - lastRefreshMillis > LOGO_TTL_MILLIS
    }

    /** The distinct URLs in [urls] that are stale at [nowMillis],
     * given the last-refresh timestamps in [lastRefresh]. */
    fun staleUrls(
        urls: List<String>,
        lastRefresh: Map<String, Long>,
        nowMillis: Long
    ): List<String> =
        urls.distinct().filter { isStale(lastRefresh[it], nowMillis) }

    /** Refresh every stale channel logo in the background: for each
     * one, force a network fetch that overwrites its disk-cache
     * entry, then stamp it refreshed. One bad URL never stops the
     * batch, and a failed fetch is never stamped — it retries on
     * the next app start. Best-effort throughout: this must never
     * break app startup. */
    suspend fun refreshStaleLogos(context: Context) {
        try {
            val channels = PlaylistRepository.loadPlaylist(context).getOrNull() ?: return
            val urls = channels.mapNotNull { it.logo }.distinct()
            if (urls.isEmpty()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val lastRefresh = urls.mapNotNull { url ->
                prefs.getLong(url, -1L).takeIf { it >= 0 }?.let { url to it }
            }.toMap()
            val now = System.currentTimeMillis()
            val stale = staleUrls(urls, lastRefresh, now).take(MAX_REFRESH_PER_RUN)
            for (url in stale) {
                try {
                    val request = ImageRequest.Builder(context)
                        .data(url)
                        // Don't read the stale disk entry, but do
                        // overwrite it; skip the HTTP cache so a
                        // stale cache header can't serve the old art.
                        .diskCachePolicy(CachePolicy.WRITE_ONLY)
                        .networkCachePolicy(CachePolicy.DISABLED)
                        .build()
                    val outcome = context.imageLoader.execute(request)
                    if (outcome is SuccessResult) {
                        prefs.edit().putLong(url, now).apply()
                    }
                } catch (ignored: Exception) {
                    // One bad logo never stops the batch.
                }
            }
        } catch (ignored: Exception) {
            // Best-effort: the refresh must never break startup.
        }
    }
}
