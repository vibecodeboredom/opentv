package com.opentv.player

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * Plays one channel with ExoPlayer. OK pauses/resumes (PlayerView controller).
 * MENU or INFO toggles the channel as a favorite.
 */
class PlaybackActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHANNEL = "channel"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var channel: Channel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)

        val ch = intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        if (ch == null) {
            finish()
            return
        }
        channel = ch

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

        val player = ExoPlayer.Builder(this).build()
        this.player = player
        playerView.player = player
        val metadata = MediaMetadata.Builder()
            .setTitle(ch.name)
            .setSubtitle(ch.group)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(ch.url).setMediaMetadata(metadata).build())
        player.prepare()
        player.play()

        // Best-effort EPG: show Now / Next for this channel once it loads.
        lifecycleScope.launch {
            val epg = PlaylistRepository.loadEpg(this@PlaybackActivity)
            val (now, next) = EpgParser.nowAndNext(epg[ch.tvgId])
            if (now != null || next != null) {
                val text = buildString {
                    now?.let { append("${getString(R.string.now_playing)}: ${it.title}") }
                    next?.let {
                        if (isNotEmpty()) append("  •  ")
                        append("${getString(R.string.next_up)}: ${it.title}")
                    }
                }
                Toast.makeText(this@PlaybackActivity, text, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            channel?.let {
                PlaylistRepository.toggleFavorite(this, it.id)
                val isFav = PlaylistRepository.favorites(this).contains(it.id)
                Toast.makeText(
                    this,
                    if (isFav) "Added to favorites" else "Removed from favorites",
                    Toast.LENGTH_SHORT
                ).show()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        player?.release()
        player = null
    }
}
