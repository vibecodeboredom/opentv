package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
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
import coil.load
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Plays one channel with ExoPlayer. The media controller is fully
 * disabled — no overlay ever pops up. OK opens the TV Guide, and
 * left/right shows a TiviMate-style channel info overlay: group +
 * date/time on top, logo + channel name, now/next with times and a
 * progress bar, and a bottom strip of tiles (TV guide, History, the
 * 3 most recent channels, Clear). While the overlay is up, left/right
 * moves between the tiles and OK activates the focused one.
 *
 * The player is built in onStart and released in onStop, so leaving
 * for the guide and coming straight back resumes the channel instead
 * of a black screen that never loads.
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
        const val EXTRA_RETRY_COUNT = "retry_count"
        /** Dead streams are common in the playlist — auto-try another random channel this many times. */
        private const val MAX_AUTO_RETRY = 3
        /** Overlay auto-hides after this long with no key press. */
        private const val OVERLAY_TIMEOUT_MS = 10_000L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var infoGroup: TextView
    private lateinit var infoDateTime: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var infoChannelName: TextView
    private lateinit var infoNowTitle: TextView
    private lateinit var infoNowMeta: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoStrip: LinearLayout
    /** Auto-hide for the overlay. */
    private val infoHandler = Handler(Looper.getMainLooper())
    private val hideInfoRunnable = Runnable { hideOverlay() }
    /** Now / next programmes, filled once EPG loads. */
    private var nowProgramme: EpgParser.Programme? = null
    private var nextProgramme: EpgParser.Programme? = null
    /** The strip tiles, in order, for left/right focus movement. */
    private val stripTiles = mutableListOf<View>()
    private var channel: Channel? = null
    private var recordingId: String? = null
    private var recordingUri: String? = null
    private var recordingName: String? = null
    private var sessionStartMillis: Long = 0L
    /** Guard so a rapid double OK press doesn't stack two guides. */
    private var guideLaunched = false
    /** True once this stream actually started playing. */
    private var everPlayed = false
    private var retryCount: Int = 0
    private var autoRetrying = false

    private val dateTimeFormat = SimpleDateFormat("EEE, MMM d, h:mm a", Locale.US)
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.channel_info)
        infoGroup = findViewById(R.id.info_group)
        infoDateTime = findViewById(R.id.info_datetime)
        infoLogo = findViewById(R.id.info_logo)
        infoChannelName = findViewById(R.id.info_channel_name)
        infoNowTitle = findViewById(R.id.info_now_title)
        infoNowMeta = findViewById(R.id.info_now_meta)
        infoNext = findViewById(R.id.info_next)
        infoProgress = findViewById(R.id.info_progress)
        infoStrip = findViewById(R.id.info_strip)
        // No media controller at all — the video just plays. Left/right
        // shows the channel info overlay instead.
        playerView.useController = false

        val ch = intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        recordingUri = intent.getStringExtra(EXTRA_RECORDING_URI)
        recordingName = intent.getStringExtra(EXTRA_RECORDING_NAME)
        retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)

        if (ch == null && recordingUri == null) {
            finish()
            return
        }
        channel = ch

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

        // Best-effort EPG: show Now / Next for this channel once it loads.
        if (ch != null) {
            lifecycleScope.launch {
                val epg = PlaylistRepository.loadEpg(this@PlaybackActivity)
                val (now, next) = EpgParser.nowAndNext(epg[ch.tvgId])
                nowProgramme = now
                nextProgramme = next
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

    /**
     * (Re)creates the player and starts playback. Called from onStart so
     * returning from the guide (which stops this activity and releases the
     * player) resumes the channel instead of leaving a black screen that
     * never loads. Guarded by player == null so onCreate -> onStart
     * doesn't build it twice.
     */
    private fun initializePlayer() {
        if (player != null) return
        val ch = channel
        val uri = recordingUri ?: ch?.url ?: return
        val title = recordingName ?: ch?.name ?: return
        val subtitle = if (recordingUri != null) {
            getString(R.string.recording_label)
        } else {
            ch.group
        }
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
        everPlayed = false
        autoRetrying = false
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        player.prepare()
        player.play()
        sessionStartMillis = System.currentTimeMillis()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !everPlayed) {
                    everPlayed = true
                    // First real playback: this channel counts as watched.
                    channel?.let { PlaylistRepository.recordRecentChannel(this@PlaybackActivity, it.id) }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Dead stream that never played: quietly try another
                // random channel instead of leaving a black screen.
                // Once a stream has played, or retries are spent, just
                // show the error like before.
                if (recordingUri == null && !everPlayed && !autoRetrying &&
                    retryCount < MAX_AUTO_RETRY
                ) {
                    autoRetrying = true
                    tryAnotherChannel()
                    return
                }
                Toast.makeText(
                    this@PlaybackActivity,
                    getString(R.string.playback_error, error.message ?: "unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // Rebuild the player if it was released in onStop (e.g. after
        // visiting the guide and coming back).
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        guideLaunched = false
    }

    /**
     * OK opens the TV Guide (or activates the focused strip tile when the
     * overlay is up). Left/right shows the overlay, and moves between the
     * strip tiles while it is up. BACK hides the overlay. Intercepted here,
     * before the PlayerView sees the key.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val overlayUp = overlay.visibility == View.VISIBLE
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (overlayUp) activateFocusedTile() else openGuide()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (overlayUp) moveStripFocus(event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
                    else showChannelInfo()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (overlayUp) {
                        hideOverlay()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Shows the channel info overlay (TiviMate-style); auto-hides after 10s idle. */
    private fun showChannelInfo() {
        val ch = channel
        val name = recordingName ?: ch?.name ?: return
        val now = System.currentTimeMillis()
        infoGroup.text = ch?.group ?: ""
        infoDateTime.text = dateTimeFormat.format(Date(now))
        infoChannelName.text = name
        infoLogo.load(ch?.logo) {
            placeholder(android.R.drawable.ic_media_play)
            error(android.R.drawable.ic_media_play)
        }
        val nowP = nowProgramme
        if (nowP != null) {
            infoNowTitle.text = nowP.title
            val mins = ((nowP.stopMillis - nowP.startMillis) / 60_000).toInt()
            val num = if (ch != null && ch.number > 0) "${ch.number} " else ""
            infoNowMeta.text = "${timeFormat.format(Date(nowP.startMillis))} – " +
                "${timeFormat.format(Date(nowP.stopMillis))}  •  $mins min  •  $num$name"
            val span = (nowP.stopMillis - nowP.startMillis).coerceAtLeast(1L)
            infoProgress.progress = ((now - nowP.startMillis) * 100 / span).toInt().coerceIn(0, 100)
        } else {
            infoNowTitle.text = getString(R.string.overlay_no_info)
            infoNowMeta.text = ""
            infoProgress.progress = 0
        }
        val nextP = nextProgramme
        infoNext.text = if (nextP != null) {
            "${timeFormat.format(Date(nextP.startMillis))} – " +
                "${timeFormat.format(Date(nextP.stopMillis))}  •  ${nextP.title}"
        } else {
            getString(R.string.overlay_no_info)
        }
        buildStrip()
        overlay.visibility = View.VISIBLE
        // Focus the first tile so left/right + OK work straight away.
        // Deferred until after layout, so the focus actually sticks.
        infoStrip.post { stripTiles.firstOrNull()?.requestFocus() }
        armHideTimer()
    }

    private fun hideOverlay() {
        overlay.visibility = View.GONE
        infoHandler.removeCallbacks(hideInfoRunnable)
    }

    private fun armHideTimer() {
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.postDelayed(hideInfoRunnable, OVERLAY_TIMEOUT_MS)
    }

    /** Builds the bottom strip: TV guide, History, 3 most recent channels, Clear. */
    private fun buildStrip() {
        val noInfo = getString(R.string.overlay_no_info)
        // Show the fixed tiles immediately; the recent channels fill in
        // once the (cached) playlist resolves their ids.
        buildStripStatic(emptyList(), noInfo)
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val byId = channels.associateBy { it.id }
            val currentId = channel?.id
            val recents = PlaylistRepository.recentChannelIds(this@PlaybackActivity)
                .filter { it != currentId }
                .mapNotNull { byId[it] }
                .take(3)
            if (overlay.visibility != View.VISIBLE) return@launch
            val keepFocus = stripTiles.indexOfFirst { it.isFocused }
            buildStripStatic(recents, noInfo)
            // Restore focus (or default to the first tile); deferred
            // until after layout so it sticks.
            infoStrip.post {
                if (keepFocus in stripTiles.indices) stripTiles[keepFocus].requestFocus()
                else stripTiles.firstOrNull()?.requestFocus()
            }
        }
    }

    /** The strip's fixed tiles plus the given recent channels, then Clear. */
    private fun buildStripStatic(recents: List<Channel>, noInfo: String) {
        infoStrip.removeAllViews()
        stripTiles.clear()
        val dp = { v: Int -> (v * resources.displayMetrics.density).toInt() }

        fun tile(iconRes: Int?, logoUrl: String?, label: String, onClick: () -> Unit) {
            val tileView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(8), dp(10), dp(8), dp(10))
                layoutParams = LinearLayout.LayoutParams(dp(150), dp(110)).apply {
                    marginEnd = dp(12)
                }
                setBackgroundColor(0xFF1C1C22.toInt())
                setOnClickListener { onClick() }
                onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    setBackgroundColor(if (hasFocus) 0xFF3B82F6.toInt() else 0xFF1C1C22.toInt())
                }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (logoUrl != null) {
                    load(logoUrl) {
                        placeholder(android.R.drawable.ic_media_play)
                        error(android.R.drawable.ic_media_play)
                    }
                } else if (iconRes != null) {
                    setImageResource(iconRes)
                }
            }
            tileView.addView(icon)
            tileView.addView(TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(6) }
            })
            // OK is handled by the activity's dispatchKeyEvent (it calls
            // performClick on the focused tile), so no per-tile key
            // listener is needed here.
            infoStrip.addView(tileView)
            stripTiles += tileView
        }

        tile(android.R.drawable.ic_menu_view, null, getString(R.string.tv_guide)) { openGuide() }
        tile(android.R.drawable.ic_menu_recent_history, null, getString(R.string.overlay_history)) { openGuide() }
        for (recent in recents) {
            tile(null, recent.logo, recent.name) { switchToChannel(recent) }
        }
        tile(android.R.drawable.ic_menu_delete, null, getString(R.string.overlay_clear)) {
            PlaylistRepository.clearRecentChannels(this)
            Toast.makeText(this, R.string.overlay_recents_cleared, Toast.LENGTH_SHORT).show()
            hideOverlay()
        }
    }

    /** Move focus to the next / previous strip tile. */
    private fun moveStripFocus(right: Boolean) {
        if (stripTiles.isEmpty()) return
        val current = stripTiles.indexOfFirst { it.isFocused }.takeIf { it >= 0 } ?: 0
        val next = if (right) (current + 1).coerceAtMost(stripTiles.size - 1)
        else (current - 1).coerceAtLeast(0)
        stripTiles[next].requestFocus()
        armHideTimer()
    }

    /** Activate whichever strip tile currently has focus. */
    private fun activateFocusedTile() {
        stripTiles.firstOrNull { it.isFocused }?.performClick()
    }

    /** Switch playback to the given channel (used by a recent-channel tile). */
    private fun switchToChannel(pick: Channel) {
        hideOverlay()
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(EXTRA_CHANNEL, pick)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    private fun openGuide() {
        if (guideLaunched) return
        guideLaunched = true
        startActivity(Intent(this, GuideActivity::class.java))
    }

    /** Pick a different random channel and restart playback with it. */
    private fun tryAnotherChannel() {
        val currentId = channel?.id
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val pick = result.getOrNull()
                ?.filter { it.id != currentId && it.id !in PlaylistRepository.hiddenChannels(this@PlaybackActivity) }
                ?.randomOrNull()
            if (pick != null) {
                val intent = Intent(this@PlaybackActivity, PlaybackActivity::class.java)
                intent.putExtra(EXTRA_CHANNEL, pick)
                intent.putExtra(EXTRA_RETRY_COUNT, retryCount + 1)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            } else {
                autoRetrying = false
                Toast.makeText(
                    this@PlaybackActivity,
                    R.string.load_failed,
                    Toast.LENGTH_LONG
                ).show()
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
        infoHandler.removeCallbacks(hideInfoRunnable)
        overlay.visibility = View.GONE
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
