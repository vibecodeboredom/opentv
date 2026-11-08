package com.opentv.player

import android.app.AlertDialog
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch

/**
 * Plays one channel with ExoPlayer. OK pauses/resumes (PlayerView controller).
 *
 * - INFO toggles the channel as a favorite.
 * - MENU opens options: favorite toggle + record / stop recording.
 *
 * Can also play back a local recording (EXTRA_RECORDING_*): the player is
 * built with the download cache as its data source, so it reads from disk.
 */
@OptIn(UnstableApi::class)
class PlaybackActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_RECORDING_URI = "recording_uri"
        const val EXTRA_RECORDING_NAME = "recording_name"
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private var channel: Channel? = null
    private var recordingId: String? = null
    private var recordingUri: String? = null
    private var recordingName: String? = null
    private var sessionStartMillis: Long = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)

        val ch = intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        recordingUri = intent.getStringExtra(EXTRA_RECORDING_URI)
        recordingName = intent.getStringExtra(EXTRA_RECORDING_NAME)

        if (ch == null && recordingUri == null) {
            finish()
            return
        }
        channel = ch

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

        val player = if (recordingUri != null) {
            // Playback from the local download cache.
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(RecordingManager.cacheDataSourceFactory(this))
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .build()
        } else {
            ExoPlayer.Builder(this).build()
        }
        this.player = player
        playerView.player = player

        val uri = recordingUri ?: ch!!.url
        val title = recordingName ?: ch!!.name
        val subtitle = if (recordingUri != null) {
            getString(R.string.recording_label)
        } else {
            ch!!.group
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        player.prepare()
        player.play()
        sessionStartMillis = System.currentTimeMillis()
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlaybackActivity,
                    getString(R.string.playback_error, error.message ?: "unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        })

        // Best-effort EPG: show Now / Next for this channel once it loads.
        if (ch != null) {
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
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                showOptions()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                toggleFavorite()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleFavorite() {
        channel?.let {
            PlaylistRepository.toggleFavorite(this, it.id)
            val isFav = PlaylistRepository.favorites(this).contains(it.id)
            Toast.makeText(
                this,
                if (isFav) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun showOptions() {
        val ch = channel
        if (ch == null) {
            // Playing a recording: no channel options.
            return
        }
        val isFav = PlaylistRepository.favorites(this).contains(ch.id)
        val isRecording = RecordingManager.isRecording(this, ch.id)
        val hasRecording = RecordingManager.hasRecording(this, ch.id)
        val items = mutableListOf<String>()
        items += if (isFav) getString(R.string.remove_favorite) else getString(R.string.add_favorite)
        items += when {
            isRecording -> getString(R.string.stop_recording)
            hasRecording -> getString(R.string.record_again)
            else -> getString(R.string.record_channel)
        }
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    0 -> toggleFavorite()
                    1 -> {
                        if (isRecording) {
                            RecordingManager.stopRecording(this, ch.id)
                            Toast.makeText(
                                this,
                                R.string.recording_stopped,
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            RecordingManager.startRecording(this, ch)
                            Toast.makeText(
                                this,
                                R.string.recording_started,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        // Log the watch session (local only, never shared).
        val watchedMillis = System.currentTimeMillis() - sessionStartMillis
        if (sessionStartMillis > 0L && watchedMillis > 0L) {
            val id = channel?.id ?: recordingId ?: "unknown"
            val name = channel?.name ?: recordingName ?: "Unknown"
            StatsManager.logWatchSession(this, id, name, watchedMillis)
        }
        sessionStartMillis = 0L
        player?.release()
        player = null
    }
}
