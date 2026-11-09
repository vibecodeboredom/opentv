package com.opentv.player

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Launcher (4.6.0): a proper TiviMate-style loading screen instead of
 * jumping straight into a random channel.
 *
 * Black background, the OpenTV logo centred, an indeterminate spinner, and
 * a 14sp white status line on a rounded pill that updates as each stage
 * finishes: "Loading playlist…" then "Loading guide…", then playback.
 *
 * Launch now resumes the LAST channel that actually played (stored in
 * SharedPreferences by PlaybackActivity); a random channel is only the
 * fallback when there is no last channel. If the playlist fails to load,
 * the screen shows a clear error message and falls back to the browse
 * menu (where Settings lives) instead of leaving a black playback screen.
 *
 * The guide load is best-effort: it warms the EPG cache so the guide opens
 * fast, but it is capped at a few seconds so a slow first EPG fetch never
 * stalls launch — PlaybackActivity and the guide load the EPG on demand.
 */
class MainActivity : FragmentActivity() {

    /** Max time to wait for the EPG warm-up before launching playback. */
    private companion object {
        const val EPG_WARMUP_TIMEOUT_MS = 3000L
    }

    private lateinit var loadingContent: View
    private lateinit var browseFrame: View
    private lateinit var spinner: ProgressBar
    private lateinit var status: TextView
    private lateinit var error: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_main)
        loadingContent = findViewById(R.id.loading_content)
        browseFrame = findViewById(R.id.browse_frame)
        spinner = findViewById(R.id.loading_spinner)
        status = findViewById(R.id.loading_status)
        error = findViewById(R.id.loading_error)

        lifecycleScope.launch {
            // Playlist and guide load in parallel; the playlist is the
            // critical path, the guide is a warm-up.
            val playlistDeferred = async { PlaylistRepository.loadPlaylist(this@MainActivity) }
            val epgDeferred = async { PlaylistRepository.loadEpg(this@MainActivity) }

            status.setText(R.string.loading_playlist)
            val result = playlistDeferred.await()
            result.onSuccess { channels ->
                val hidden = PlaylistRepository.hiddenChannels(this@MainActivity)
                // Resume the last channel that actually played; random
                // only when there is none (or it is no longer listed).
                // No filtered copy of the whole playlist — the launch
                // path used to build one just to pick a channel from
                // it. The fallback order matches the old code exactly:
                // last visible → random visible → last (even hidden,
                // when everything is hidden) → random of all.
                val lastId = PlaylistRepository.lastChannelId(this@MainActivity)
                val pick = channels.firstOrNull { it.id == lastId && it.id !in hidden }
                    ?: randomVisibleChannel(channels, hidden)
                    ?: channels.firstOrNull { it.id == lastId }
                    ?: channels.randomOrNull()
                if (pick == null) {
                    showBrowse()
                    return@onSuccess
                }
                // 6.0.0-rc1: mobile does NOT auto-play on launch (that
                // would blast sound + data the moment the app opens).
                // It goes to the touch home screen instead; the
                // playlist is already warm in the cache, so the list
                // is instant. The EPG warm-up wait is skipped too —
                // the guide loads it on demand.
                if (DeviceType.isMobile(this@MainActivity)) {
                    startActivity(Intent(this@MainActivity, MobileHomeActivity::class.java))
                    finish()
                    return@onSuccess
                }
                // Warm the guide cache, briefly — never stall launch on it.
                status.setText(R.string.loading_guide)
                withTimeoutOrNull(EPG_WARMUP_TIMEOUT_MS) { epgDeferred.await() }
                status.setText(R.string.loading_starting)
                val intent = Intent(this@MainActivity, PlaybackActivity::class.java)
                intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, pick)
                startActivity(intent)
                finish()
            }.onFailure { cause ->
                showLoadError(cause)
            }
        }
    }

    /**
     * Playlist failed: say so plainly, then fall back to the browse
     * menu. A local-file failure shows its own message (file missing /
     * no channels) instead of the generic "check your connection"
     * line, so it is diagnosable; everything else keeps the generic
     * message. The fallback itself is unchanged.
     */
    private fun showLoadError(cause: Throwable? = null) {
        spinner.visibility = View.GONE
        status.visibility = View.GONE
        error.visibility = View.VISIBLE
        val message = cause?.message
        error.text = if (message != null && message.startsWith("Local playlist")) {
            message
        } else {
            getString(R.string.load_failed)
        }
        showBrowse()
    }

    private fun showBrowse() {
        // 6.0.0-rc1: on mobile the fallback is the touch home screen
        // (it loads the playlist itself and shows its own error +
        // retry); the Leanback browse menu is TV-only.
        if (DeviceType.isMobile(this)) {
            startActivity(Intent(this, MobileHomeActivity::class.java))
            finish()
            return
        }
        // The browse fragment goes in its own container; the loading
        // content hides, and the error banner (a sibling of both) stays
        // visible over the browse menu when loading failed.
        loadingContent.visibility = View.GONE
        browseFrame.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.browse_frame, BrowseFragment())
            .commit()
    }

    /** One random visible channel, allocation-free: counts the visible
     * channels, picks a random index, then walks to it. Returns null
     * when no channel is visible (the caller falls back to the full
     * list, as the old filtered-list code did). */
    private fun randomVisibleChannel(channels: List<Channel>, hidden: Set<String>): Channel? {
        var visibleCount = 0
        for (ch in channels) if (ch.id !in hidden) visibleCount++
        if (visibleCount == 0) return null
        val target = (0 until visibleCount).random()
        var seen = 0
        for (ch in channels) {
            if (ch.id !in hidden) {
                if (seen == target) return ch
                seen++
            }
        }
        return null
    }
}
