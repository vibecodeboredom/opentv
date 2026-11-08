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
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
import androidx.media3.exoplayer.DefaultLoadControl
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
 * disabled — OpenTV draws its own UI instead.
 *
 * 4.6.0 (TiviMate-style) key map — documented here because the remote
 * is the whole interface:
 * - OK (short): sidebar up → activate the focused row; info overlay up →
 *   activate the focused tile; nothing up → open the TV Guide. If playback
 *   is paused and nothing is up, OK resumes instead of opening the guide.
 * - OK (long-press, ~600ms, nothing up): open the context menu (the same
 *   options MENU shows: favorite, record). Implemented with a delayed
 *   runnable: the short-press action fires on key-UP, so a hold never
 *   also triggers the short-press action.
 * - LEFT: sidebar up → back (channels → groups, groups → close); overlay
 *   up → move between strip tiles (only when a strip tile has focus, so
 *   the transport buttons keep normal Left/Right focus movement);
 *   nothing up → open the left sidebar (channels/groups).
 * - RIGHT: sidebar up → activate the focused row; overlay up → move
 *   between strip tiles (same focus caveat as Left); nothing up → show
 *   the channel info overlay.
 * - UP/DOWN: move focus between rows/tiles/buttons (framework focus
 *   search; the ScrollView scrolls to the focused sidebar row).
 * - BACK: sidebar up → back/close; overlay up → hide it; else the
 *   system default (leave playback).
 * - Play/pause key (remote): toggle pause/resume. Channel up/down keys:
 *   next / previous channel.
 * - MENU opens options (favorite + record). INFO toggles favorite.
 *
 * The left sidebar (4.6.1) is a 260dp panel over a 60% black scrim:
 * groups at the top ("All channels", "Favorites", "History", then the
 * playlist groups), channels below with number + logo + name + the
 * current programme. The current channel is highlighted in the accent
 * (#86C739); the focused row gets a rounded 16dp highlight and its text
 * turns accent. Channel rows are capped at 200 (the footer says so) so
 * opening the sidebar never has to build thousands of views.
 *
 * The player is built in onStart and released in onStop, so leaving
 * for the guide and coming straight back resumes the channel instead
 * of a black screen that never loads.
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
        /** Hold OK this long to open the context menu instead of the guide. */
        private const val LONG_PRESS_MS = 600L
        /** Sidebar channel rows are capped at this many (see the footer). */
        private const val SIDEBAR_MAX_CHANNELS = 200
        /** Sidebar group keys (pseudo-groups first, then playlist groups). */
        private const val GROUP_ALL = "ALL"
        private const val GROUP_FAVORITES = "FAV"
        private const val GROUP_HISTORY = "HIST"
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
    /** Bottom transport bar: title + icon-only buttons. */
    private lateinit var transportTitle: TextView
    private lateinit var transportPlayPause: ImageButton
    private lateinit var transportRecord: ImageButton
    private lateinit var transportFavorite: ImageButton
    /** Centred play icon shown while paused. */
    private lateinit var pausedCluster: ImageView
    /** Left sidebar + scrim. */
    private lateinit var sidebarScrim: View
    private lateinit var sidebar: View
    private lateinit var sidebarTitle: TextView
    private lateinit var sidebarSubtitle: TextView
    private lateinit var sidebarScroll: ScrollView
    private lateinit var sidebarList: LinearLayout
    private lateinit var sidebarFooter: TextView
    /** Auto-hide for the overlay. */
    private val infoHandler = Handler(Looper.getMainLooper())
    private val hideInfoRunnable = Runnable { hideOverlay() }
    /** Long-press OK: opens the context menu. */
    private val longPressRunnable = Runnable {
        longPressFired = true
        showOptions()
    }
    private var longPressFired = false
    /** The recents the strip was last built with, so an unchanged list
     * doesn't rebuild the whole strip a second time. */
    private var lastStripRecents: List<String> = emptyList()
    /** Sidebar / overlay state captured when OK goes down, so the
     * short-press action on key-UP uses the state at press time. */
    private var okDownSidebarUp = false
    private var okDownOverlayUp = false
    /** Now / next programmes, filled once EPG loads. */
    private var nowProgramme: EpgParser.Programme? = null
    private var nextProgramme: EpgParser.Programme? = null
    /** The strip tiles, in order, for left/right focus movement. */
    private val stripTiles = mutableListOf<View>()
    /** Sidebar state: are we showing the groups list, and which group
     * (GROUP_ALL / GROUP_FAVORITES / GROUP_HISTORY / a group name). */
    private var sidebarInGroups = true
    private var sidebarGroup = GROUP_ALL
    /** Channel id -> "now playing" TextView, so the EPG fill-in can update
     * sidebar rows after they are built (same pattern as the strip). */
    private val sidebarProgrammeViews = mutableMapOf<String, TextView>()
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
        transportTitle = findViewById(R.id.transport_title)
        transportPlayPause = findViewById(R.id.transport_play_pause)
        transportRecord = findViewById(R.id.transport_record)
        transportFavorite = findViewById(R.id.transport_favorite)
        pausedCluster = findViewById(R.id.paused_cluster)
        sidebarScrim = findViewById(R.id.sidebar_scrim)
        sidebar = findViewById(R.id.sidebar)
        sidebarTitle = findViewById(R.id.sidebar_title)
        sidebarSubtitle = findViewById(R.id.sidebar_subtitle)
        sidebarScroll = findViewById(R.id.sidebar_scroll)
        sidebarList = findViewById(R.id.sidebar_list)
        sidebarFooter = findViewById(R.id.sidebar_footer)
        // No media controller at all — the video just plays. The overlay
        // and sidebar are OpenTV's own UI.
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

        // Transport buttons (bottom bar). Play/pause toggles playback;
        // prev/next switch channel; record and favorite are the same
        // actions the MENU options dialog offers.
        findViewById<ImageButton>(R.id.transport_prev).setOnClickListener {
            switchToAdjacentChannel(-1)
        }
        transportPlayPause.setOnClickListener { togglePlayPause() }
        findViewById<ImageButton>(R.id.transport_next).setOnClickListener {
            switchToAdjacentChannel(1)
        }
        transportRecord.setOnClickListener { toggleRecording() }
        transportFavorite.setOnClickListener {
            toggleFavorite()
            updateTransportIcons()
        }
        pausedCluster.setOnClickListener { togglePlayPause() }
        sidebarScrim.setOnClickListener { hideSidebar() }
        updateTransportIcons()

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
            ch?.group ?: ""
        }
        // Start playback after a short buffer (0.5s) instead of the
        // default 2.5s, so switching channels feels instant instead of
        // sitting on a black screen. The min/max buffers stay generous
        // so a slow stream doesn't rebuffer right after starting.
        val fastStartLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 10_000,
                /* maxBufferMs */ 30_000,
                /* bufferForPlaybackMs */ 500,
                /* bufferForPlaybackAfterRebufferMs */ 1_500
            )
            .build()
        val player = if (recordingUri != null) {
            // Playback from the local download cache.
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(RecordingManager.cacheDataSourceFactory(this))
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(fastStartLoadControl)
                .build()
        } else {
            ExoPlayer.Builder(this)
                .setLoadControl(fastStartLoadControl)
                .build()
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
                    // First real playback: this channel counts as watched,
                    // and it becomes the channel launch resumes next time.
                    channel?.let {
                        PlaylistRepository.recordRecentChannel(this@PlaybackActivity, it.id)
                        PlaylistRepository.setLastChannelId(this@PlaybackActivity, it.id)
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused: show the centred play cluster; playing: hide it.
                // The transport play/pause icon follows the same state.
                pausedCluster.visibility = if (isPlaying) View.GONE else View.VISIBLE
                updateTransportIcons()
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
     * Key handling — see the class comment for the full map. OK is handled
     * on key-UP (with a long-press timer armed on key-DOWN) so a hold
     * opens the context menu without also firing the short-press action.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sidebarUp = sidebar.visibility == View.VISIBLE
        val overlayUp = overlay.visibility == View.VISIBLE
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    okDownSidebarUp = sidebarUp
                    okDownOverlayUp = overlayUp
                    // Long-press OK (nothing up) opens the context menu.
                    if (!sidebarUp && !overlayUp) {
                        longPressFired = false
                        infoHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (sidebarUp) {
                        if (right) sidebarActivate() else sidebarBack()
                        return true
                    }
                    if (overlayUp) {
                        // Only steer the strip when a strip tile has
                        // focus; the transport buttons below it keep
                        // normal Left/Right focus movement.
                        if (stripTiles.any { it.isFocused }) {
                            moveStripFocus(right)
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                    // Nothing up: Left opens the sidebar, Right shows
                    // the channel info overlay.
                    if (right) showChannelInfo() else showSidebar()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (sidebarUp) {
                        sidebarBack()
                        return true
                    }
                    if (overlayUp) {
                        hideOverlay()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    switchToAdjacentChannel(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    infoHandler.removeCallbacks(longPressRunnable)
                    if (longPressFired) {
                        // The long-press already opened the menu.
                        longPressFired = false
                        return true
                    }
                    if (okDownSidebarUp) {
                        sidebarActivate()
                    } else if (okDownOverlayUp) {
                        activateFocusedTile()
                    } else if (player?.isPlaying == false) {
                        // Paused with nothing up: OK resumes.
                        togglePlayPause()
                    } else {
                        openGuide()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Pause / resume, and keep the cluster + transport icon in sync. */
    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        // onIsPlayingChanged updates the cluster and the icon.
    }

    /** Refresh the transport bar icons for the current state. Uses the
     * modern vector icons (4.6.1) — no more dated system drawables. The
     * record dot turns red while recording instead of dimming. */
    private fun updateTransportIcons() {
        val p = player
        transportPlayPause.setImageResource(
            if (p != null && !p.isPlaying) R.drawable.ic_play
            else R.drawable.ic_pause
        )
        val ch = channel
        val isFav = ch != null && PlaylistRepository.favorites(this).contains(ch.id)
        transportFavorite.setImageResource(
            if (isFav) R.drawable.ic_star_filled
            else R.drawable.ic_star
        )
        transportRecord.setImageResource(R.drawable.ic_record)
        val isRecording = ch != null && RecordingManager.isRecording(this, ch.id)
        // Red dot while recording; white when idle. Alpha stays 1.0 —
        // the colour is the state now.
        transportRecord.setColorFilter(
            if (isRecording) 0xFFFF4343.toInt() else 0xFFFFFFFF.toInt()
        )
        transportRecord.alpha = 1.0f
    }

    /** Start / stop recording this channel (same action as MENU offers). */
    private fun toggleRecording() {
        val ch = channel ?: return
        if (RecordingManager.isRecording(this, ch.id)) {
            RecordingManager.stopRecording(this, ch.id)
            Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_SHORT).show()
        } else {
            RecordingManager.startRecording(this, ch)
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        }
        updateTransportIcons()
    }

    /** Switch to the channel before / after the current one in the playlist. */
    private fun switchToAdjacentChannel(delta: Int) {
        val currentId = channel?.id ?: return
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            val visible = channels.filter { it.id !in hidden }
            val list = visible.ifEmpty { channels }
            val idx = list.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: return@launch
            val next = list[(idx + delta).mod(list.size)]
            switchToChannel(next)
        }
    }

    // ------------------------------------------------------------------
    // Left sidebar (4.6.0)
    // ------------------------------------------------------------------

    /** Open the sidebar on the groups list; hides the info overlay. */
    private fun showSidebar() {
        if (recordingUri != null) return // Recordings have no channel list.
        hideOverlay()
        sidebarInGroups = true
        sidebarGroup = GROUP_ALL
        sidebarScrim.visibility = View.VISIBLE
        sidebar.visibility = View.VISIBLE
        buildSidebarGroups()
    }

    private fun hideSidebar() {
        sidebar.visibility = View.GONE
        sidebarScrim.visibility = View.GONE
        sidebarProgrammeViews.clear()
    }

    /** Left / BACK inside the sidebar: channels -> groups, groups -> close. */
    private fun sidebarBack() {
        if (!sidebarInGroups) {
            sidebarInGroups = true
            buildSidebarGroups()
        } else {
            hideSidebar()
        }
    }

    /** OK / Right inside the sidebar: open a group, or switch channel. */
    private fun sidebarActivate() {
        val focused = sidebarListFocusedRow() ?: return
        val tag = focused.tag
        when (tag) {
            is SidebarGroup -> {
                sidebarInGroups = false
                sidebarGroup = tag.key
                buildSidebarChannels(tag.key)
            }
            is Channel -> switchToChannel(tag)
        }
    }

    /** The sidebar row (group or channel) that currently has focus. */
    private fun sidebarListFocusedRow(): View? {
        for (i in 0 until sidebarList.childCount) {
            val row = sidebarList.getChildAt(i)
            if (row.isFocused) return row
        }
        return null
    }

    private data class SidebarGroup(val key: String, val label: String, val count: Int)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** The groups list: All channels, Favorites, History, then playlist groups.
     * Builds synchronously when the playlist is already in memory. */
    private fun buildSidebarGroups() {
        sidebarTitle.setText(R.string.sidebar_groups)
        sidebarSubtitle.text = ""
        sidebarFooter.text = ""
        sidebarList.removeAllViews()
        sidebarProgrammeViews.clear()
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            renderSidebarGroups(cached)
            return
        }
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: emptyList()
            if (sidebar.visibility != View.VISIBLE || !sidebarInGroups) return@launch
            renderSidebarGroups(channels)
        }
    }

    /** Render the groups list from a channel list. Runs on the main thread. */
    private fun renderSidebarGroups(channels: List<Channel>) {
        val hidden = PlaylistRepository.hiddenChannels(this)
        val visible = channels.filter { it.id !in hidden }
        val favorites = PlaylistRepository.favorites(this)
        val recents = PlaylistRepository.recentChannelIds(this)
        // Single pass for the per-group counts (was one full scan per group).
        val groupCounts = visible.groupingBy { it.group }.eachCount()
        val items = mutableListOf<SidebarGroup>()
        items += SidebarGroup(GROUP_ALL, getString(R.string.sidebar_all_channels), visible.size)
        items += SidebarGroup(
            GROUP_FAVORITES, getString(R.string.favorites),
            visible.count { it.id in favorites }
        )
        items += SidebarGroup(
            GROUP_HISTORY, getString(R.string.sidebar_history), recents.size
        )
        for ((g, count) in groupCounts) {
            items += SidebarGroup(g, g, count)
        }
        for (item in items) {
            sidebarList.addView(buildSidebarGroupRow(item))
        }
        if (items.isEmpty()) {
            sidebarFooter.setText(R.string.sidebar_no_groups)
        }
        // Focus the "All channels" row so Up/Down works straight away.
        sidebarList.post {
            (sidebarList.getChildAt(0) as? View)?.requestFocus()
        }
    }

    /** One group row: name + channel count, accent text when focused. */
    private fun buildSidebarGroupRow(item: SidebarGroup): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            minimumHeight = dp(56)
            setBackgroundResource(R.drawable.sidebar_row_bg)
            tag = item
            setOnClickListener {
                sidebarInGroups = false
                sidebarGroup = item.key
                buildSidebarChannels(item.key)
            }
        }
        val nameView = TextView(this).apply {
            text = item.label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countView = TextView(this).apply {
            text = item.count.toString()
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(nameView)
        row.addView(countView)
        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            nameView.setTextColor(if (hasFocus) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt())
        }
        return row
    }

    /** The channels of one group (or a pseudo-group), capped at 200 rows.
     * Builds synchronously when the playlist is already in memory. */
    private fun buildSidebarChannels(groupKey: String) {
        val groupLabel = when (groupKey) {
            GROUP_ALL -> getString(R.string.sidebar_all_channels)
            GROUP_FAVORITES -> getString(R.string.favorites)
            GROUP_HISTORY -> getString(R.string.sidebar_history)
            else -> groupKey
        }
        sidebarTitle.text = groupLabel
        sidebarList.removeAllViews()
        sidebarProgrammeViews.clear()
        sidebarFooter.text = ""
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            renderSidebarChannels(groupKey, cached)
            return
        }
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: emptyList()
            if (sidebar.visibility != View.VISIBLE || sidebarInGroups) return@launch
            renderSidebarChannels(groupKey, channels)
        }
    }

    /** Render one group's channels from a channel list. Main thread. */
    private fun renderSidebarChannels(groupKey: String, channels: List<Channel>) {
        val hidden = PlaylistRepository.hiddenChannels(this)
        val visible = channels.filter { it.id !in hidden }
        val favorites = PlaylistRepository.favorites(this)
        val recents = PlaylistRepository.recentChannelIds(this)
        val byId = channels.associateBy { it.id }
        val list = when (groupKey) {
            GROUP_ALL -> visible
            GROUP_FAVORITES -> visible.filter { it.id in favorites }
            GROUP_HISTORY -> recents.mapNotNull { byId[it] }.filter { it.id !in hidden }
            else -> visible.filter { it.group == groupKey }
        }
        sidebarSubtitle.text = getString(R.string.sidebar_channels_count, list.size)
        val shown = list.take(SIDEBAR_MAX_CHANNELS)
        for (ch in shown) {
            sidebarList.addView(buildSidebarChannelRow(ch))
        }
        if (list.isEmpty()) {
            sidebarFooter.setText(R.string.sidebar_no_channels)
        } else if (list.size > SIDEBAR_MAX_CHANNELS) {
            sidebarFooter.text = getString(R.string.sidebar_first_n, SIDEBAR_MAX_CHANNELS)
        }
        // Focus the current channel's row (or the first row) so
        // Up/Down works straight away.
        val currentId = channel?.id
        sidebarList.post {
            val target = (0 until sidebarList.childCount)
                .map { sidebarList.getChildAt(it) }
                .firstOrNull { (it.tag as? Channel)?.id == currentId }
                ?: sidebarList.getChildAt(0)
            target?.requestFocus()
            // Keep the focused row visible once the list is laid out.
            sidebarScroll.post {
                (0 until sidebarList.childCount)
                    .map { sidebarList.getChildAt(it) }
                    .firstOrNull { it.isFocused }
                    ?.let { sidebarScroll.scrollTo(0, it.top) }
            }
        }
        fillSidebarNowPlaying(shown)
    }

    /** Fill in each shown row's "now playing" programme. Uses the cached
     * EPG when it is already in memory; otherwise loads it once and
     * fills in when it arrives. */
    private fun fillSidebarNowPlaying(shown: List<Channel>) {
        val cached = PlaylistRepository.cachedEpg()
        if (cached != null) {
            applySidebarEpg(cached, shown)
            return
        }
        lifecycleScope.launch {
            val epg = PlaylistRepository.loadEpg(this@PlaybackActivity)
            if (sidebar.visibility != View.VISIBLE || sidebarInGroups) return@launch
            applySidebarEpg(epg, shown)
        }
    }

    private fun applySidebarEpg(
        epg: Map<String, List<EpgParser.Programme>>,
        shown: List<Channel>
    ) {
        val now = System.currentTimeMillis()
        val noInfo = getString(R.string.overlay_no_info)
        for (ch in shown) {
            val view = sidebarProgrammeViews[ch.id] ?: continue
            val programmes = epg[ch.tvgId] ?: epg[ch.name.lowercase()]
            val current = EpgParser.nowAndNext(programmes, now).first
            view.text = current?.title ?: noInfo
        }
    }

    /** One channel row: number + logo + name + now-playing programme. */
    private fun buildSidebarChannelRow(ch: Channel): View {
        val isCurrent = ch.id == channel?.id
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            minimumHeight = dp(56)
            setBackgroundResource(R.drawable.sidebar_row_bg)
            tag = ch
            setOnClickListener { switchToChannel(ch) }
        }
        val numberView = TextView(this).apply {
            text = if (ch.number > 0) ch.number.toString() else ""
            // The current channel's number is accent; the rest are 70% white.
            setTextColor(if (isCurrent) 0xFF86C739.toInt() else 0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val logoView = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(ch.logo) {
                placeholder(R.drawable.ic_play)
                error(R.drawable.ic_play)
            }
        }
        val textBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(this).apply {
            text = ch.name
            // Current channel in accent, the rest white.
            setTextColor(if (isCurrent) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val programmeView = TextView(this).apply {
            text = getString(R.string.overlay_no_info)
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textBlock.addView(nameView)
        textBlock.addView(programmeView)
        row.addView(numberView)
        row.addView(logoView)
        row.addView(textBlock)
        sidebarProgrammeViews[ch.id] = programmeView
        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            // Focused row: name (and number, unless it is the current
            // channel, which is already accent) turns accent.
            nameView.setTextColor(
                if (hasFocus || isCurrent) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt()
            )
            if (!isCurrent) {
                numberView.setTextColor(if (hasFocus) 0xFF86C739.toInt() else 0xB3FFFFFF.toInt())
            }
        }
        return row
    }

    // ------------------------------------------------------------------
    // Info overlay (restyled 4.6.0)
    // ------------------------------------------------------------------

    /** Shows the channel info overlay; auto-hides after 10s idle. */
    private fun showChannelInfo() {
        hideSidebar()
        val ch = channel
        val name = recordingName ?: ch?.name ?: return
        val now = System.currentTimeMillis()
        infoGroup.text = ch?.group ?: ""
        infoDateTime.text = dateTimeFormat.format(Date(now))
        infoChannelName.text = name
        infoLogo.load(ch?.logo) {
            placeholder(R.drawable.ic_play)
            error(R.drawable.ic_play)
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
        // Bottom bar title: the programme on now, or the channel name.
        transportTitle.text = nowP?.title ?: name
        updateTransportIcons()
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
        infoHandler.removeCallbacks(longPressRunnable)
    }

    private fun armHideTimer() {
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.postDelayed(hideInfoRunnable, OVERLAY_TIMEOUT_MS)
    }

    /** Builds the bottom strip: TV guide, History, 3 most recent channels, Clear.
     * Common case: the playlist is already in memory, so the strip is built
     * once, with recents — instead of building it empty and rebuilding it
     * a frame later. When the playlist isn't cached yet, the fixed tiles
     * show immediately and the recents fill in once it loads (skipped
     * entirely when the recents are unchanged from the last build). */
    private fun buildStrip() {
        val noInfo = getString(R.string.overlay_no_info)
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            buildStripStatic(stripRecents(cached), noInfo)
            return
        }
        buildStripStatic(emptyList(), noInfo)
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val recents = stripRecents(channels)
            if (overlay.visibility != View.VISIBLE) return@launch
            if (recents.map { it.id } == lastStripRecents) return@launch
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

    /** The 3 most recent channels (not the current one) from a list. */
    private fun stripRecents(channels: List<Channel>): List<Channel> {
        val byId = channels.associateBy { it.id }
        val currentId = channel?.id
        return PlaylistRepository.recentChannelIds(this)
            .filter { it != currentId }
            .mapNotNull { byId[it] }
            .take(3)
    }

    /** The strip's fixed tiles plus the given recent channels, then Clear.
     * 4.6.1: modern look — rounded tile_bg selector (accent on focus) and
     * fresh vector icons instead of the dated system drawables. The label
     * and icon turn dark on the green focused tile so they stay readable;
     * channel logos are never tinted. */
    private fun buildStripStatic(recents: List<Channel>, noInfo: String) {
        infoStrip.removeAllViews()
        stripTiles.clear()

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
                setBackgroundResource(R.drawable.tile_bg)
                setOnClickListener { onClick() }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (logoUrl != null) {
                    load(logoUrl) {
                        placeholder(R.drawable.ic_play)
                        error(R.drawable.ic_play)
                    }
                } else if (iconRes != null) {
                    setImageResource(iconRes)
                }
            }
            val labelView = TextView(this).apply {
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
            }
            tileView.addView(icon)
            tileView.addView(labelView)
            tileView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                // Focused tile is accent green — dark text/icon on it.
                // Logos keep their own colours.
                val dark = 0xFF121218.toInt()
                labelView.setTextColor(if (hasFocus) dark else 0xFFFFFFFF.toInt())
                if (iconRes != null) {
                    icon.setColorFilter(if (hasFocus) dark else 0xFFFFFFFF.toInt())
                }
            }
            // OK is handled by the activity's dispatchKeyEvent (it calls
            // performClick on the focused tile), so no per-tile key
            // listener is needed here.
            infoStrip.addView(tileView)
            stripTiles += tileView
        }

        tile(R.drawable.ic_guide, null, getString(R.string.tv_guide)) { openGuide() }
        tile(R.drawable.ic_history, null, getString(R.string.overlay_history)) { openGuide() }
        for (recent in recents) {
            tile(null, recent.logo, recent.name) { switchToChannel(recent) }
        }
        tile(R.drawable.ic_clear, null, getString(R.string.overlay_clear)) {
            PlaylistRepository.clearRecentChannels(this)
            Toast.makeText(this, R.string.overlay_recents_cleared, Toast.LENGTH_SHORT).show()
            hideOverlay()
        }
        lastStripRecents = recents.map { it.id }
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
        hideSidebar()
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(EXTRA_CHANNEL, pick)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    private fun openGuide() {
        if (guideLaunched) return
        guideLaunched = true
        val intent = Intent(this, GuideActivity::class.java)
        // The guide highlights the current channel in the accent colour.
        channel?.let { intent.putExtra(GuideActivity.EXTRA_CURRENT_CHANNEL_ID, it.id) }
        startActivity(intent)
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
                updateTransportIcons()
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
                    0 -> {
                        toggleFavorite()
                        updateTransportIcons()
                    }
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
                        updateTransportIcons()
                    }
                }
            }
            .show()
    }

    override fun onStop() {
        super.onStop()
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.removeCallbacks(longPressRunnable)
        overlay.visibility = View.GONE
        hideSidebar()
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
