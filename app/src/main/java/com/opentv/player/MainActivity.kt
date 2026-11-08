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
                val visible = channels.filter { it.id !in hidden }
                val pool = visible.ifEmpty { channels }
                // Resume the last channel that actually played; random
                // only when there is none (or it is no longer listed).
                val lastId = PlaylistRepository.lastChannelId(this@MainActivity)
                val pick = pool.firstOrNull { it.id == lastId } ?: pool.randomOrNull()
                if (pick == null) {
                    showBrowse()
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
            }.onFailure {
                showLoadError()
            }
        }
    }

    /** Playlist failed: say so plainly, then fall back to the browse menu. */
    private fun showLoadError() {
        spinner.visibility = View.GONE
        status.visibility = View.GONE
        error.visibility = View.VISIBLE
        error.setText(R.string.load_failed)
        showBrowse()
    }

    private fun showBrowse() {
        // The browse fragment goes in its own container; the loading
        // content hides, and the error banner (a sibling of both) stays
        // visible over the browse menu when loading failed.
        loadingContent.visibility = View.GONE
        browseFrame.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.browse_frame, BrowseFragment())
            .commit()
    }
}
