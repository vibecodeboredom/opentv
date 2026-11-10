package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
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
 *   search; the sidebar's RecyclerView scrolls to the focused row).
 * - BACK: sidebar up → back/close; overlay up → hide it; else the
 *   system default (leave playback).
 * - Play/pause key (remote): toggle pause/resume. Channel up/down keys:
 *   next / previous channel.
 * - MENU opens options (favorite + record). INFO toggles favorite.
 *
 * The left sidebar (4.6.2, shared ChannelSidebar helper) is a panel
 * over a 60% black scrim (6.1.2: width from the channel-list size
 * setting — 220dp compact default): groups at the top ("All channels",
 * "Favorites", "History", then the playlist groups), channels below
 * with number + logo + name + the current programme. The current
 * channel is highlighted in the accent (#86C739); the focused row gets
 * a rounded 16dp highlight and its text turns accent. Channel rows
 * are capped at 200 (the footer says so); 6.0.0-rc2 made the list a
 * RecyclerView, so only the visible rows are bound.
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
        /** 5.0.0 — catch-up: the resolved catch-up URL plus the
         * programme it replays (title + window), from the guide. */
        const val EXTRA_CATCHUP_URL = "catchup_url"
        const val EXTRA_CATCHUP_TITLE = "catchup_title"
        const val EXTRA_CATCHUP_START_MS = "catchup_start_ms"
        const val EXTRA_CATCHUP_STOP_MS = "catchup_stop_ms"
        /** Set when this activity was started by a channel up/down press:
         * the info overlay should flash briefly instead of the normal
         * Left/Right duration. */
        const val EXTRA_SHOW_BRIEF_INFO = "show_brief_info"
        /** Dead streams are common in the playlist — auto-try another random channel this many times. */
        private const val MAX_AUTO_RETRY = 3
        /** Overlay auto-hides after this long with no key press. */
        private const val OVERLAY_TIMEOUT_MS = 10_000L
        /** 6.0.0-rc3: mobile overlay auto-hide — 5s idle, then it
         * fades away. TV keeps the 10s timeout above. */
        private const val MOBILE_OVERLAY_TIMEOUT_MS = 5_000L
        /** 6.0.0-rc3: mobile overlay fade-out duration. */
        private const val OVERLAY_FADE_MS = 200L
        /** Channel up/down flash: half the Left/Right overlay time, so
         * zapping past a channel shows its info only briefly. */
        private const val CHANNEL_SWITCH_OVERLAY_TIMEOUT_MS = 5_000L
        /** Hold OK this long to open the context menu instead of the guide. */
        private const val LONG_PRESS_MS = 600L
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
    private lateinit var transportBar: LinearLayout
    private lateinit var transportTitle: TextView
    private lateinit var transportPlayPause: ImageButton
    private lateinit var transportRecord: ImageButton
    private lateinit var transportFavorite: ImageButton
    /** 6.0.0-rc1: "Channels" button, mobile only — opens the sidebar
     * (there is no Left key on a phone). 6.1.6: part of the
     * auto-hiding overlay group — it shows and fades with the
     * overlay (see showMobileOverlayViews / hideOverlay). */
    private lateinit var channelsButton: TextView
    /** 6.0.0-rc3: mobile-only top "Guide" button + centred channel
     * info. Both are part of the auto-hiding overlay group on
     * mobile: they show and fade with the bottom overlay, and never
     * appear on TV. */
    private lateinit var mobileGuideButton: TextView
    private lateinit var mobileCenterInfo: LinearLayout
    private lateinit var mobileCenterLogo: ImageView
    private lateinit var mobileCenterChannel: TextView
    private lateinit var mobileCenterNow: TextView
    private lateinit var mobileCenterNext: TextView
    /** Centred play icon shown while paused. */
    private lateinit var pausedCluster: ImageView
    /** Left sidebar (4.6.2: shared ChannelSidebar helper). */
    private lateinit var channelSidebar: ChannelSidebar
    /** Auto-hide for the overlay. */
    private val infoHandler = Handler(Looper.getMainLooper())
    private val hideInfoRunnable = Runnable { hideOverlay() }
    /** True while the overlay up is a channel up/down flash (short
     * timeout); the first Left/Right press upgrades it to the normal
     * Left/Right timeout instead. */
    private var overlayBrief = false
    /** Long-press OK: opens the context menu. */
    private val longPressRunnable = Runnable {
        longPressFired = true
        showOptions()
    }
    private var longPressFired = false
    /** The recents the strip was last built with, so an unchanged list
     * doesn't rebuild the whole strip a second time. */
    private var lastStripRecents: List<String> = emptyList()
    /** Whether the strip was last built in catch-up mode (5.0.0): the
     * Go-live tile appears/disappears with it, so a mode change must
     * rebuild even when the recents are unchanged. */
    private var lastStripCatchup: Boolean = false
    /** Channel id -> Channel for the strip's recents (4.9.5): the map
     * used to be rebuilt from the whole playlist on every overlay
     * open. It is rebuilt only when the playlist list itself changes
     * (cachedPlaylist returns the same instance while fresh). */
    private var stripByIdPlaylist: List<Channel>? = null
    private var stripById: Map<String, Channel> = emptyMap()
    /** Sidebar / overlay state captured when OK goes down, so the
     * short-press action on key-UP uses the state at press time. */
    private var okDownSidebarUp = false
    private var okDownOverlayUp = false
    /** Now / next programmes, filled once EPG loads. */
    private var nowProgramme: EpgParser.Programme? = null
    private var nextProgramme: EpgParser.Programme? = null
    /** Live now / next from the EPG, kept separately (5.0.0): a
     * catch-up session shows the replayed programme as "now" and the
     * live programme as "next", and needs the live pair to fall back
     * to when catch-up fails or the user goes live. */
    private var liveNow: EpgParser.Programme? = null
    private var liveNext: EpgParser.Programme? = null
    /** 5.0.0 — catch-up session state (all null = live playback). */
    private var catchupUrl: String? = null
    private var catchupTitle: String? = null
    private var catchupStartMillis: Long = 0L
    private var catchupStopMillis: Long = 0L
    /** True once a failed catch-up has already fallen back to live. */
    private var catchupFallbackTried = false
    /** Cached favorite ids (see GuideActivity — same pattern). The
     * transport icons read favorites on every play/pause change and
     * every overlay open, and the old code read the set out of
     * SharedPreferences on each one. Refreshed on resume (the user
     * may have favorited from the guide) and after every toggle
     * made here. */
    private var favoritesCache: Set<String> = emptySet()
    /** Last logo URL loaded into the info overlay — showChannelInfo
     * re-runs when the EPG lands, and the old code re-issued a Coil
     * load of the same logo on every one. */
    private var infoLogoUrl: String? = null
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
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
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
        transportBar = findViewById(R.id.transport_bar)
        transportTitle = findViewById(R.id.transport_title)
        transportPlayPause = findViewById(R.id.transport_play_pause)
        transportRecord = findViewById(R.id.transport_record)
        transportFavorite = findViewById(R.id.transport_favorite)
        pausedCluster = findViewById(R.id.paused_cluster)
        channelsButton = findViewById(R.id.mobile_channels_button)
        // 6.0.0-rc3: mobile-only overlay group (top Guide button +
        // centred channel info). Bound here but only ever shown on
        // mobile — TV never touches them.
        mobileGuideButton = findViewById(R.id.mobile_guide_button)
        mobileCenterInfo = findViewById(R.id.mobile_center_info)
        mobileCenterLogo = findViewById(R.id.mobile_center_logo)
        mobileCenterChannel = findViewById(R.id.mobile_center_channel)
        mobileCenterNow = findViewById(R.id.mobile_center_now)
        mobileCenterNext = findViewById(R.id.mobile_center_next)
        // Left sidebar (shared ChannelSidebar helper, 4.6.2).
        channelSidebar = ChannelSidebar(
            activity = this,
            scope = lifecycleScope,
            scrim = findViewById(R.id.sidebar_scrim),
            panel = findViewById(R.id.sidebar),
            title = findViewById(R.id.sidebar_title),
            subtitle = findViewById(R.id.sidebar_subtitle),
            list = findViewById(R.id.sidebar_scroll),
            footer = findViewById(R.id.sidebar_footer),
            currentChannelId = { channel?.id },
            onChannelSelected = { switchToChannel(it) },
            sizing = AppSettings.sidebarSizing(this)
        )
        // No media controller at all — the video just plays. The overlay
        // and sidebar are OpenTV's own UI.
        playerView.useController = false

        val ch = intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        recordingUri = intent.getStringExtra(EXTRA_RECORDING_URI)
        recordingName = intent.getStringExtra(EXTRA_RECORDING_NAME)
        // 5.0.0 — catch-up: a resolved replay URL + its programme.
        catchupUrl = intent.getStringExtra(EXTRA_CATCHUP_URL)
        catchupTitle = intent.getStringExtra(EXTRA_CATCHUP_TITLE)
        catchupStartMillis = intent.getLongExtra(EXTRA_CATCHUP_START_MS, 0L)
        catchupStopMillis = intent.getLongExtra(EXTRA_CATCHUP_STOP_MS, 0L)
        retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
        // Started by a channel up/down press: flash the info overlay
        // briefly once the EPG is in, instead of the now/next toast.
        val briefInfo = intent.getBooleanExtra(EXTRA_SHOW_BRIEF_INFO, false)

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
        findViewById<View>(R.id.sidebar_scrim).setOnClickListener { hideSidebar() }
        favoritesCache = PlaylistRepository.favorites(this)
        updateTransportIcons()
        // 6.0.0-rc1: touch controls on mobile (tap to toggle the
        // overlay, swipe to change channel, big transport buttons).
        if (DeviceType.isMobile(this)) setupMobileTouch()

        // Best-effort EPG: show Now / Next for this channel once it loads.
        if (ch != null) {
            lifecycleScope.launch {
                val epg = PlaylistRepository.loadEpg(this@PlaybackActivity)
                val (now, next) = EpgParser.nowAndNext(epg[ch.tvgId])
                liveNow = now
                liveNext = next
                // 5.0.0 — catch-up: the replayed programme is "now" and
                // the live programme is "next" (the way back to live).
                if (catchupUrl != null) {
                    nowProgramme = catchupProgramme()
                    nextProgramme = now
                } else {
                    nowProgramme = now
                    nextProgramme = next
                }
                if (overlay.visibility == View.VISIBLE) {
                    // The overlay was opened before the EPG finished
                    // loading and still shows "No information" — refresh
                    // it with the real now/next data, keeping its
                    // current 5s/10s hide mode.
                    showChannelInfo(brief = overlayBrief)
                } else if (briefInfo) {
                    // Channel up/down: flash the info overlay briefly
                    // with the real now/next data, instead of the toast
                    // (the overlay shows the same info). Only if the
                    // user hasn't already opened something else while
                    // the EPG was loading — a late flash must not yank
                    // the sidebar away or shorten a normal overlay.
                    if (!channelSidebar.isVisible() &&
                        overlay.visibility != View.VISIBLE
                    ) {
                        showChannelInfo(brief = true)
                    }
                } else if (catchupUrl != null) {
                    Toast.makeText(
                        this@PlaybackActivity,
                        getString(R.string.catchup_playing, catchupTitle ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                } else if (now != null || next != null) {
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
     * Touch controls for mobile (6.0.0-rc1). TV is untouched — this
     * only runs on phones/tablets:
     * - The "Channels" button appears with the overlay (there is no
     *   Left key to open the sidebar with) and fades with it.
     * - The transport bar and its buttons grow to finger size.
     * - Tapping the video toggles the info overlay; a horizontal
     *   fling changes channel. The tap/fling detector sits on the
     *   PlayerView, so taps on the overlay or sidebar never reach it.
     * 6.0.0-rc3: the overlay (bottom info + top Guide button +
     * centred channel info) auto-hides 5s after the last touch
     * (see armHideTimer + dispatchTouchEvent); tapping the video
     * shows it again.
     */
    private fun setupMobileTouch() {
        // 6.1.6: the "Channels" button is part of the overlay group —
        // it only shows while the overlay is up (see showChannelInfo /
        // hideOverlay), like the top Guide button. It used to be forced
        // VISIBLE here and never faded, so it sat alone on screen after
        // the 5s auto-hide. Its click opens the sidebar.
        channelsButton.setOnClickListener { showSidebar() }
        // 6.0.0-rc3: the top Guide button is part of the overlay
        // group — it only shows while the overlay is up (see
        // showChannelInfo / hideOverlay). Its click opens the guide.
        mobileGuideButton.setOnClickListener { openGuide() }
        transportBar.layoutParams.height = dp(56)
        for (id in listOf(
            R.id.transport_prev, R.id.transport_play_pause, R.id.transport_next,
            R.id.transport_record, R.id.transport_favorite
        )) {
            val button = findViewById<ImageButton>(id)
            button.layoutParams.width = dp(48)
            button.layoutParams.height = dp(48)
        }
        // Mutating LayoutParams fields does not schedule a layout
        // pass by itself — ask for one (the overlay is hidden until
        // first shown, so this is belt-and-braces).
        transportBar.requestLayout()
        val gestures = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (channelSidebar.isVisible()) hideSidebar()
                    else if (overlay.visibility == View.VISIBLE) hideOverlay()
                    else showChannelInfo()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                        kotlin.math.abs(dx) > dp(80)
                    ) {
                        switchToAdjacentChannel(if (dx < 0) 1 else -1)
                        return true
                    }
                    return false
                }
            })
        playerView.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            false
        }
    }

    /** 5.0.0: the replayed programme as a synthetic Programme, so the
     * info panel can show it as "now" with its real time window. */
    private fun catchupProgramme(): EpgParser.Programme? {
        val title = catchupTitle ?: return null
        if (catchupStartMillis <= 0L || catchupStopMillis <= catchupStartMillis) return null
        return EpgParser.Programme(
            channelId = channel?.tvgId ?: channel?.id ?: "",
            startMillis = catchupStartMillis,
            stopMillis = catchupStopMillis,
            title = title
        )
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
        // 5.0.0 — catch-up: the replay URL wins over the live one.
        val uri = catchupUrl ?: recordingUri ?: ch?.url ?: return
        val title = catchupTitle ?: recordingName ?: ch?.name ?: return
        val subtitle = if (catchupUrl != null) {
            getString(R.string.catchup_label)
        } else if (recordingUri != null) {
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
                    // 6.1.7: on mobile the overlay shows with the video
                    // on first playback, then fades after 5s (see
                    // armHideTimer). Before this the buttons never
                    // appeared until the video was tapped — and since
                    // 6.1.6 the Channels button fades too, a fresh
                    // start showed nothing at all. Skip when the
                    // overlay is already up, when a channel up/down
                    // brief flash is coming (the EPG callback shows
                    // it), or during catch-up (the EPG callback
                    // toasts instead). TV is untouched.
                    if (DeviceType.isMobile(this@PlaybackActivity) &&
                        overlay.visibility != View.VISIBLE &&
                        !intent.getBooleanExtra(EXTRA_SHOW_BRIEF_INFO, false) &&
                        catchupUrl == null
                    ) {
                        showChannelInfo()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused: show the centred play cluster; playing: hide it.
                // The transport play/pause icon follows the same state.
                pausedCluster.visibility = if (isPlaying) View.GONE else View.VISIBLE
                // 6.0.0-rc3: while paused the big centre play icon owns
                // the middle of the screen, so the centred info panel
                // steps aside; it returns when playback resumes, but
                // only if the overlay is still up.
                if (DeviceType.isMobile(this@PlaybackActivity)) {
                    if (isPlaying) {
                        if (overlay.visibility == View.VISIBLE) {
                            showMobileView(mobileCenterInfo)
                        }
                    } else {
                        mobileCenterInfo.animate().cancel()
                        mobileCenterInfo.visibility = View.GONE
                        mobileCenterInfo.alpha = 1f
                    }
                }
                updateTransportIcons()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 5.0.0 — catch-up that never played: fall back to the
                // live stream ONCE instead of jumping to a random
                // channel. If the live stream fails too, the normal
                // auto-retry below applies (catchupUrl is null now).
                if (catchupUrl != null && !everPlayed && !catchupFallbackTried) {
                    catchupFallbackTried = true
                    catchupUrl = null
                    catchupTitle = null
                    nowProgramme = liveNow
                    nextProgramme = liveNext
                    this@PlaybackActivity.player?.release()
                    this@PlaybackActivity.player = null
                    Toast.makeText(
                        this@PlaybackActivity,
                        R.string.catchup_failed_live,
                        Toast.LENGTH_LONG
                    ).show()
                    initializePlayer()
                    return
                }
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
        // The user may have favorited this channel from the guide.
        favoritesCache = PlaylistRepository.favorites(this)
    }

    /**
     * Key handling — see the class comment for the full map. OK is handled
     * on key-UP (with a long-press timer armed on key-DOWN) so a hold
     * opens the context menu without also firing the short-press action.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sidebarUp = channelSidebar.isVisible()
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
                        // A channel up/down flash upgrades to the normal
                        // Left/Right timeout the first time the user
                        // steers it — after that it behaves exactly like
                        // a normal overlay (no timer reset on later keys).
                        if (overlayBrief) {
                            overlayBrief = false
                            armHideTimer()
                        }
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

    /**
     * 6.0.0-rc3, mobile only: any touch while the overlay is up
     * restarts the 5s auto-hide timer — tapping a transport button,
     * a strip tile, or the guide button counts as interaction just
     * like tapping the video. TV is untouched (its 10s timer is
     * key-driven). The sidebar/guide paths never reach this with
     * the overlay visible: showSidebar hides the overlay first,
     * and opening the guide stops the activity (onStop removes the
     * callbacks), so the timer can't fire while either is open.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (DeviceType.isMobile(this) && ev.action == MotionEvent.ACTION_DOWN &&
            overlay.visibility == View.VISIBLE
        ) {
            armHideTimer(overlayBrief)
        }
        return super.dispatchTouchEvent(ev)
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
        val isFav = ch != null && ch.id in favoritesCache
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
            // 4.9.5: find the adjacent channel without building a
            // filtered copy of the whole playlist (was a ~1500-element
            // list per CH+/CH- press). Two allocation-free passes:
            // count the visible channels and the current channel's
            // visible position, then pick the channel at the target
            // visible position. Falls back to the full list when every
            // channel is hidden, exactly like the old ifEmpty.
            var visibleIndex = -1
            var visibleCount = 0
            for (ch in channels) {
                if (ch.id in hidden) continue
                if (ch.id == currentId) visibleIndex = visibleCount
                visibleCount++
            }
            val next: Channel? = if (visibleCount > 0) {
                if (visibleIndex < 0) return@launch
                val target = (visibleIndex + delta).mod(visibleCount)
                var seen = 0
                var found: Channel? = null
                for (ch in channels) {
                    if (ch.id in hidden) continue
                    if (seen == target) {
                        found = ch
                        break
                    }
                    seen++
                }
                found
            } else {
                val idx = channels.indexOfFirst { it.id == currentId }
                    .takeIf { it >= 0 } ?: return@launch
                channels[(idx + delta).mod(channels.size)]
            }
            val pick = next ?: return@launch
            // Channel up/down restarts playback on the new channel and
            // flashes its info briefly (4.7.3) instead of the normal
            // Left/Right overlay duration.
            switchToChannel(pick, briefInfo = true)
        }
    }

    // ------------------------------------------------------------------
    // Left sidebar (4.6.2: shared ChannelSidebar helper)
    // ------------------------------------------------------------------

    /** Open the sidebar on the groups list; hides the info overlay. */
    private fun showSidebar() {
        if (recordingUri != null) return // Recordings have no channel list.
        hideOverlay()
        channelSidebar.show()
    }

    private fun hideSidebar() {
        channelSidebar.hide()
    }

    /** Left / BACK inside the sidebar: channels -> groups, groups -> close. */
    private fun sidebarBack() {
        channelSidebar.back()
    }

    /** OK / Right inside the sidebar: open a group, or switch channel. */
    private fun sidebarActivate() {
        channelSidebar.activate()
    }


    // ------------------------------------------------------------------
    // Info overlay (restyled 4.6.0)
    // ------------------------------------------------------------------

    /** Shows the channel info overlay; auto-hides after 10s idle.
     * With [brief] (channel up/down), it hides after the shorter
     * 5s flash timeout instead. 6.0.0-rc3: on mobile the timeout is
     * always 5s, and the top Guide button + centred channel info
     * show with it. */
    private fun showChannelInfo(brief: Boolean = false) {
        hideSidebar()
        val ch = channel
        val name = recordingName ?: ch?.name ?: return
        val now = System.currentTimeMillis()
        infoGroup.text = ch?.group ?: ""
        // DateFormat.format(long) takes the millis directly — the old
        // code wrapped each one in a throwaway Date (3 per overlay open).
        infoDateTime.text = dateTimeFormat.format(now)
        infoChannelName.text = name
        // Skip re-loading the same logo — but only for a real URL: a
        // null logo (recording playback) still loads every time (Coil
        // shows the placeholder), exactly as before. 6.1.0:
        // downsampled to the 60dp info logo.
        val logoUrl = ch?.logo
        if (logoUrl == null || logoUrl != infoLogoUrl) {
            infoLogoUrl = logoUrl
            infoLogo.loadLogo(logoUrl, 60)
        }
        val nowP = nowProgramme
        if (nowP != null) {
            // 5.0.0 — catch-up: the replayed programme gets the ⏪
            // marker, and the meta line says so. The progress bar is
            // wall-clock based, so it hides during catch-up — a full
            // bar under a finished show would be misleading.
            infoNowTitle.text =
                if (catchupUrl != null) "⏪ ${nowP.title}" else nowP.title
            val mins = ((nowP.stopMillis - nowP.startMillis) / 60_000).toInt()
            val num = if (ch != null && ch.number > 0) "${ch.number} " else ""
            infoNowMeta.text = "${timeFormat.format(nowP.startMillis)} – " +
                "${timeFormat.format(nowP.stopMillis)}  •  $mins min  •  $num$name" +
                if (catchupUrl != null) "  •  ⏪ ${getString(R.string.catchup_label)}" else ""
            infoNowMeta.visibility = View.VISIBLE
            val span = (nowP.stopMillis - nowP.startMillis).coerceAtLeast(1L)
            infoProgress.progress = ((now - nowP.startMillis) * 100 / span).toInt().coerceIn(0, 100)
            infoProgress.visibility = if (catchupUrl != null) View.GONE else View.VISIBLE
        } else {
            infoNowTitle.text = getString(R.string.overlay_no_info)
            infoNowMeta.text = ""
            infoNowMeta.visibility = View.GONE
            infoProgress.progress = 0
        }
        val nextP = nextProgramme
        if (nextP != null) {
            infoNext.text = "${timeFormat.format(nextP.startMillis)} – " +
                "${timeFormat.format(nextP.stopMillis)}  •  ${nextP.title}"
            infoNext.visibility = View.VISIBLE
        } else {
            // No next programme: hide the line instead of a second
            // "No information" under the big now-title.
            infoNext.visibility = View.GONE
        }
        // Bottom bar title: the programme on now, or the channel name.
        transportTitle.text = nowP?.title ?: name
        updateTransportIcons()
        buildStrip()
        overlay.visibility = View.VISIBLE
        // 5.0.0 — neat animations: the overlay fades in quickly
        // (150ms) instead of popping in, so channel switches feel
        // smoother. Fast enough to never delay the info itself.
        // Cancel any fade-out in flight first, or its end action
        // would hide the overlay again after this show.
        overlay.animate().cancel()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(150).start()
        // 6.0.0-rc3: on mobile the top Guide button and centred
        // channel info show and fade with the overlay.
        if (DeviceType.isMobile(this)) {
            updateMobileCenterInfo()
            showMobileOverlayViews()
        }
        // Focus the first tile so left/right + OK work straight away.
        // Deferred until after layout, so the focus actually sticks.
        infoStrip.post { stripTiles.firstOrNull()?.requestFocus() }
        overlayBrief = brief
        armHideTimer(brief)
    }

    private fun hideOverlay() {
        overlayBrief = false
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.removeCallbacks(longPressRunnable)
        if (DeviceType.isMobile(this)) {
            // 6.0.0-rc3: the overlay fades out (200ms) instead of
            // popping off, and the top Guide button + centred info
            // fade with it. 6.1.6: the Channels button fades with
            // them too — it used to stay on screen forever. TV keeps
            // the instant hide.
            fadeOutOverlayView(overlay)
            fadeOutOverlayView(channelsButton)
            fadeOutOverlayView(mobileGuideButton)
            fadeOutOverlayView(mobileCenterInfo)
        } else if (AppSettings.tvInfoFade(this)) {
            // 6.1.2: the TV info overlay fades out (200ms) like the
            // mobile one instead of popping off — the user reported
            // it never faded. Turn it off in Settings to go back to
            // the instant hide.
            fadeOutOverlayView(overlay)
        } else {
            overlay.visibility = View.GONE
        }
    }

    /** 6.0.0-rc3: fade an overlay view out, then hide it (mobile;
     * 6.1.2: the TV info overlay uses it too). Cancelling a fade-out
     * in flight (a re-show does this) never runs its end action, so
     * a re-shown view can't be hidden again by a stale fade. Alpha
     * is reset so the next show starts clean. */
    private fun fadeOutOverlayView(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        view.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    /** 6.0.0-rc3: show the mobile-only overlay group (top Guide
     * button + centred channel info) with the same 150ms fade-in
     * as the bottom overlay. 6.1.6: the Channels button shows
     * with them too. The centred panel steps aside while
     * paused — the big centre play icon owns the middle of the
     * screen then (see onIsPlayingChanged). */
    private fun showMobileOverlayViews() {
        showMobileView(channelsButton)
        showMobileView(mobileGuideButton)
        if (player?.isPlaying != false) showMobileView(mobileCenterInfo)
    }

    /** 6.0.0-rc3: fade one mobile overlay view in (150ms). */
    private fun showMobileView(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(150).start()
    }

    /** 6.0.0-rc3: fill the mobile-only centred info panel from the
     * same data as the bottom overlay (channel + now/next), so the
     * two never disagree. Called on every overlay show, including
     * the EPG-landing refresh. */
    private fun updateMobileCenterInfo() {
        val ch = channel
        val name = recordingName ?: ch?.name ?: return
        val num = if (ch != null && ch.number > 0) "${ch.number}  " else ""
        mobileCenterChannel.text = "$num$name"
        val logoUrl = ch?.logo
        // 6.1.0: downsampled to the 72dp centre logo.
        mobileCenterLogo.loadLogo(logoUrl, 72)
        val nowP = nowProgramme
        if (nowP != null) {
            val title = if (catchupUrl != null) "⏪ ${nowP.title}" else nowP.title
            mobileCenterNow.text = "${getString(R.string.now_playing)}: $title"
            mobileCenterNow.visibility = View.VISIBLE
        } else {
            mobileCenterNow.text = getString(R.string.overlay_no_info)
            mobileCenterNow.visibility = View.VISIBLE
        }
        val nextP = nextProgramme
        if (nextP != null) {
            mobileCenterNext.text = "${getString(R.string.next_up)}: ${nextP.title}"
            mobileCenterNext.visibility = View.VISIBLE
        } else {
            mobileCenterNext.visibility = View.GONE
        }
    }

    private fun armHideTimer(brief: Boolean = false) {
        infoHandler.removeCallbacks(hideInfoRunnable)
        val timeout = when {
            // 6.0.0-rc3: mobile auto-hides after 5s idle.
            DeviceType.isMobile(this) -> MOBILE_OVERLAY_TIMEOUT_MS
            brief -> CHANNEL_SWITCH_OVERLAY_TIMEOUT_MS
            else -> OVERLAY_TIMEOUT_MS
        }
        infoHandler.postDelayed(hideInfoRunnable, timeout)
    }

    /** Display density, cached once — dp() used to read
     * resources.displayMetrics on every call (up to ~90 calls per
     * strip build). */
    private val density: Float by lazy { resources.displayMetrics.density }

    private fun dp(v: Int): Int = (v * density).toInt()

    /** Builds the bottom strip: TV guide, History, 3 most recent channels, Clear.
     * Common case: the playlist is already in memory, so the strip is built
     * once, with recents — instead of building it empty and rebuilding it
     * a frame later. When the playlist isn't cached yet, the fixed tiles
     * show immediately and the recents fill in once it loads (skipped
     * entirely when the recents are unchanged from the last build).
     * 4.9.5: the cached path also skips the rebuild when the recents
     * are unchanged and the strip is already built — every Right press
     * and channel-switch flash used to remove and rebuild all the
     * tiles (views + listeners + logo loads) for the same recents. */
    private fun buildStrip() {
        val noInfo = getString(R.string.overlay_no_info)
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            val recents = stripRecents(cached)
            if (recents.map { it.id } == lastStripRecents &&
                (catchupUrl != null) == lastStripCatchup && stripTiles.isNotEmpty()
            ) return
            buildStripStatic(recents, noInfo)
            return
        }
        buildStripStatic(emptyList(), noInfo)
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val recents = stripRecents(channels)
            if (overlay.visibility != View.VISIBLE) return@launch
            if (recents.map { it.id } == lastStripRecents &&
                (catchupUrl != null) == lastStripCatchup
            ) return@launch
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
        if (stripByIdPlaylist !== channels) {
            stripById = channels.associateBy { it.id }
            stripByIdPlaylist = channels
        }
        val currentId = channel?.id
        return PlaylistRepository.recentChannelIds(this)
            .filter { it != currentId }
            .mapNotNull { stripById[it] }
            .take(3)
    }

    /** The strip's fixed tiles plus the given recent channels, then Clear.
     * 4.6.1: modern look — rounded tile_bg selector (accent on focus) and
     * fresh vector icons instead of the dated system drawables. The label
     * and icon turn dark on the green focused tile so they stay readable;
     * channel logos are never tinted.
     * 4.6.3: tiles are smaller and more compact (120x84dp, 32dp icons,
     * 13sp labels) so the strip takes up less of the screen.
     * 4.7.1: tiles are shorter still (120x60dp, 24dp icons, 12sp labels)
     * so the whole info overlay sits well below the middle of the screen. */
    private fun buildStripStatic(recents: List<Channel>, noInfo: String) {
        infoStrip.removeAllViews()
        stripTiles.clear()

        fun tile(iconRes: Int?, logoUrl: String?, label: String, onClick: () -> Unit) {
            // 6.0.0-rc1: tiles are bigger on mobile (finger targets).
            val tileWidth = if (DeviceType.isMobile(this)) dp(120) else dp(100)
            val tileHeight = if (DeviceType.isMobile(this)) dp(64) else dp(50)
            val labelSize = if (DeviceType.isMobile(this)) 12f else 10f
            val tileView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                    marginEnd = dp(6)
                }
                setBackgroundResource(R.drawable.tile_bg)
                setOnClickListener { onClick() }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (logoUrl != null) {
                    // 6.1.0: downsampled to the 20dp tile icon.
                    loadLogo(logoUrl, 20)
                } else if (iconRes != null) {
                    setImageResource(iconRes)
                }
            }
            val labelView = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1) }
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
        // 5.0.0 — catch-up: a "Go live" tile first in the strip, so the
        // way back to the live stream is one OK press away. (CH+/CH-,
        // the sidebar, and tapping the live programme in the guide all
        // go live too — they start a fresh playback without catch-up.)
        if (catchupUrl != null) {
            tile(R.drawable.ic_play, null, getString(R.string.catchup_go_live)) { goLive() }
        }
        for (recent in recents) {
            tile(null, recent.logo, recent.name) { switchToChannel(recent) }
        }
        tile(R.drawable.ic_clear, null, getString(R.string.overlay_clear)) {
            PlaylistRepository.clearRecentChannels(this)
            Toast.makeText(this, R.string.overlay_recents_cleared, Toast.LENGTH_SHORT).show()
            hideOverlay()
        }
        lastStripRecents = recents.map { it.id }
        lastStripCatchup = catchupUrl != null
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
    private fun switchToChannel(pick: Channel, briefInfo: Boolean = false) {
        hideOverlay()
        hideSidebar()
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(EXTRA_CHANNEL, pick)
        if (briefInfo) intent.putExtra(EXTRA_SHOW_BRIEF_INFO, true)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    /** 5.0.0: leave a catch-up replay and return to the live stream
     * of the same channel (the strip's "Go live" tile). Rebuilds the
     * player on the live URL and shows the live info panel. */
    private fun goLive() {
        if (channel == null) return
        catchupUrl = null
        catchupTitle = null
        nowProgramme = liveNow
        nextProgramme = liveNext
        player?.release()
        player = null
        hideOverlay()
        initializePlayer()
        showChannelInfo()
    }

    private fun openGuide() {
        if (guideLaunched) return
        guideLaunched = true
        val intent = Intent(this, GuideActivity::class.java)
        // The guide highlights the current channel in the accent colour.
        channel?.let { intent.putExtra(GuideActivity.EXTRA_CURRENT_CHANNEL_ID, it.id) }
        startActivity(intent)
    }

    /** Pick a different random channel and restart playback with it.
     * Allocation-free (same two-pass pattern 4.9.5 used for channel
     * up/down): the old code built a filtered copy of the whole
     * playlist just to pick one random channel from it. */
    private fun tryAnotherChannel() {
        val currentId = channel?.id
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull()
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            var visibleCount = 0
            if (channels != null) {
                for (ch in channels) {
                    if (ch.id != currentId && ch.id !in hidden) visibleCount++
                }
            }
            val pick: Channel? = if (visibleCount == 0 || channels == null) {
                null
            } else {
                val target = (0 until visibleCount).random()
                var seen = 0
                var found: Channel? = null
                for (ch in channels) {
                    if (ch.id != currentId && ch.id !in hidden) {
                        if (seen == target) {
                            found = ch
                            break
                        }
                        seen++
                    }
                }
                found
            }
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
            favoritesCache = PlaylistRepository.favorites(this)
            val isFav = it.id in favoritesCache
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
        val isFav = ch.id in favoritesCache
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
        // 6.0.0-rc3: the mobile overlay group hides instantly here
        // (no fade — the activity is leaving). Otherwise the Guide
        // button / centre info could sit visible with no timer
        // running when the user comes back from the guide.
        if (DeviceType.isMobile(this)) {
            for (view in listOf(mobileGuideButton, mobileCenterInfo)) {
                view.animate().cancel()
                view.visibility = View.GONE
                view.alpha = 1f
            }
        }
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
