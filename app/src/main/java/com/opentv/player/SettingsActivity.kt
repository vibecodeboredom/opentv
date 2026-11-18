package com.opentv.player

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lets the user manage M3U playlists (4.8.0: several playlists, one
 * active at a time), change the (optional) XMLTV EPG URL and the
 * (optional) recording upload URL. Also shows an About section with the
 * current app version, and (4.7.1) a "Share playlist" section that
 * temporarily hosts a playlist on this TV's Wi-Fi so other devices on
 * the same network can import it — the server stops itself after
 * 10 minutes. (4.8.0) While sharing is on, other devices can also send
 * a playlist TO this TV, which is added to the playlist list.
 * (4.9.0) Playlists can be deactivated (long-press a row) without
 * deleting them, and the screen got a modern card-style look. */
class SettingsActivity : FragmentActivity() {

    /** Ticks once a second while sharing, to refresh the countdown. */
    private val shareUiHandler = Handler(Looper.getMainLooper())
    private var shareTicker: Runnable? = null

    /** Share views, cached once — updateShareUi runs once a second
     * while sharing, and the old code did two findViewById walks per
     * tick. */
    private lateinit var shareStatusView: TextView
    private lateinit var shareButtonView: Button

    /** Local M3U file picker for the share's "local file" source. */
    private val pickM3uFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, R.string.share_file_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                startSharing(text)
            }
        }

    /** Backup file picker (8.0.0): pick an opentv-backup.json to
     * restore from. */
    private val pickBackupFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val data = BackupManager.decode(text).getOrElse {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val report = withContext(Dispatchers.IO) {
                    BackupManager.restore(this@SettingsActivity, data)
                }
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.backup_imported,
                        report.playlists, report.favorites, report.epgSources
                    ),
                    Toast.LENGTH_LONG
                ).show()
                restartToMain()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

        val uploadUrl = findViewById<EditText>(R.id.upload_url)
        uploadUrl.setText(PlaylistRepository.uploadUrl(this))

        buildPlaylistsUi()
        buildEpgSourcesUi()

        // 6.1.2: guide & playback preferences — toggles save
        // immediately; the channel-list size applies the next time
        // playback or the guide opens a fresh sidebar. 6.1.4: the
        // same size also sets the TV guide row height (thin rows on
        // Small), applied the next time the guide opens.
        findViewById<Switch>(R.id.pref_guide_current_channel).apply {
            isChecked = AppSettings.guideOpensOnCurrentChannel(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setGuideOpensOnCurrentChannel(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_tv_info_fade).apply {
            isChecked = AppSettings.tvInfoFade(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setTvInfoFade(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_guide_show_past).apply {
            isChecked = AppSettings.guideShowsPast(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setGuideShowsPast(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_guide_favorites_only).apply {
            isChecked = AppSettings.guideFavoritesOnly(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setGuideFavoritesOnly(this@SettingsActivity, checked)
            }
        }
        findViewById<RadioGroup>(R.id.pref_channel_list_size).apply {
            check(
                when (AppSettings.channelListSize(this@SettingsActivity)) {
                    AppSettings.ChannelListSize.SMALL -> R.id.pref_size_small
                    AppSettings.ChannelListSize.MEDIUM -> R.id.pref_size_medium
                    AppSettings.ChannelListSize.LARGE -> R.id.pref_size_large
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setChannelListSize(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_size_medium -> AppSettings.ChannelListSize.MEDIUM
                        R.id.pref_size_large -> AppSettings.ChannelListSize.LARGE
                        else -> AppSettings.ChannelListSize.SMALL
                    }
                )
            }
        }
        // 7.2.0: guide window size (6/12/24 h forward). The guide
        // picks it up when it opens, or on return when it is open.
        findViewById<RadioGroup>(R.id.pref_guide_window_size).apply {
            check(
                when (AppSettings.guideWindowSize(this@SettingsActivity)) {
                    AppSettings.GuideWindowSize.TWELVE -> R.id.pref_window_12h
                    AppSettings.GuideWindowSize.TWENTY_FOUR -> R.id.pref_window_24h
                    else -> R.id.pref_window_6h
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setGuideWindowSize(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_window_12h -> AppSettings.GuideWindowSize.TWELVE
                        R.id.pref_window_24h -> AppSettings.GuideWindowSize.TWENTY_FOUR
                        else -> AppSettings.GuideWindowSize.SIX
                    }
                )
            }
        }

        shareStatusView = findViewById(R.id.share_status)
        shareButtonView = findViewById(R.id.share_button)

        findViewById<Button>(R.id.add_playlist_button).setOnClickListener {
            showAddPlaylistDialog()
        }

        findViewById<Button>(R.id.add_epg_source_button).setOnClickListener {
            showAddEpgSourceDialog()
        }

        findViewById<Button>(R.id.export_backup_button).setOnClickListener {
            exportBackup()
        }

        findViewById<Button>(R.id.import_backup_button).setOnClickListener {
            try {
                pickBackupFile.launch("*/*")
            } catch (e: Exception) {
                // Some TVs have no document picker at all.
                Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_SHORT).show()
            }
        }

        // 8.0.0: the EPG sources save immediately when added or
        // removed (like the playlists), so Save only writes the
        // recording upload URL now.
        findViewById<Button>(R.id.save_button).setOnClickListener {
            PlaylistRepository.setUploadUrl(this, uploadUrl.text.toString().trim())
            finish()
        }

        findViewById<Button>(R.id.reset_button).setOnClickListener {
            PlaylistRepository.resetUrls(this)
            uploadUrl.setText("")
            buildPlaylistsUi()
            buildEpgSourcesUi()
        }

        findViewById<Button>(R.id.unhide_button).setOnClickListener {
            PlaylistRepository.unhideAllChannels(this)
        }

        findViewById<Button>(R.id.share_button).setOnClickListener {
            if (PlaylistShareServer.isRunning) {
                PlaylistShareServer.stop()
                updateShareUi()
            } else {
                showShareSourceDialog()
            }
        }
        updateShareUi()
    }

    override fun onResume() {
        super.onResume()
        updateShareUi()
        startShareTicker()
    }

    override fun onPause() {
        super.onPause()
        stopShareTicker()
    }

    /** Restart the app's loading flow with the newly active playlist. */
    private fun restartToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    // ---- Playlists (4.8.0) -------------------------------------------------

    /** One row per playlist: name + URL, the active one marked in green.
     * Tap to activate (and reload); long-press for Rename / Activate or
     * Deactivate / Remove. A disabled playlist (4.9.0) is dimmed with a
     * "Disabled" label and can't be activated by tapping — long-press
     * to re-enable it. */
    private fun buildPlaylistsUi() {
        val container = findViewById<LinearLayout>(R.id.playlists_list)
        container.removeAllViews()
        val active = PlaylistRepository.activePlaylist(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        for (playlist in PlaylistRepository.playlists(this)) {
            val isActive = playlist.url == active.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this).apply {
                text = playlist.name
                // Disabled playlists are dimmed; the active name is white.
                setTextColor(if (playlist.enabled) 0xFFFFFFFF.toInt() else 0xFF7A7A85.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val urlView = TextView(this).apply {
                text = playlist.url
                setTextColor(0xFFAAAAAA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            texts.addView(nameView)
            texts.addView(urlView)
            row.addView(texts)
            if (isActive) {
                val activeView = TextView(this).apply {
                    text = getString(R.string.playlist_active)
                    setTextColor(0xFF86C739.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), 0, 0, 0)
                }
                row.addView(activeView)
            } else if (!playlist.enabled) {
                val disabledView = TextView(this).apply {
                    text = getString(R.string.playlist_disabled)
                    setTextColor(0xFF7A7A85.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), 0, 0, 0)
                }
                row.addView(disabledView)
            }
            row.setOnClickListener {
                if (isActive) return@setOnClickListener
                if (!playlist.enabled) {
                    Toast.makeText(
                        this, R.string.playlist_disabled_hint, Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (PlaylistRepository.setActivePlaylist(this, playlist.url)) {
                    Toast.makeText(
                        this, getString(R.string.playlist_switched, playlist.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartToMain()
                }
            }
            row.setOnLongClickListener {
                showPlaylistOptionsDialog(playlist)
                true
            }
            container.addView(row)
        }
    }

    /** Rename / Activate or Deactivate / Remove for one playlist
     * (long-press on its row). Deactivating the active playlist
     * switches to the first remaining enabled one and reloads. */
    private fun showPlaylistOptionsDialog(playlist: PlaylistRepository.Playlist) {
        val toggleLabel = if (playlist.enabled) {
            getString(R.string.playlist_deactivate)
        } else {
            getString(R.string.playlist_activate)
        }
        val items = arrayOf(
            getString(R.string.playlist_rename),
            toggleLabel,
            getString(R.string.playlist_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlist)
                    1 -> {
                        val wasActive =
                            PlaylistRepository.activePlaylist(this).url == playlist.url
                        val newEnabled = !playlist.enabled
                        if (PlaylistRepository.setPlaylistEnabled(this, playlist.url, newEnabled)) {
                            Toast.makeText(
                                this,
                                getString(
                                    if (newEnabled) R.string.playlist_activated
                                    else R.string.playlist_deactivated,
                                    playlist.name
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            // Deactivating the active playlist switched
                            // the active one — reload with it.
                            if (!newEnabled && wasActive) restartToMain()
                            else buildPlaylistsUi()
                        } else {
                            Toast.makeText(
                                this, R.string.playlist_cannot_deactivate_last,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.playlist_remove_confirm, playlist.name))
                            .setPositiveButton(R.string.playlist_remove) { _, _ ->
                                val wasActive =
                                    PlaylistRepository.activePlaylist(this).url == playlist.url
                                if (PlaylistRepository.removePlaylist(this, playlist.url)) {
                                    Toast.makeText(
                                        this, getString(R.string.playlist_removed, playlist.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    // Removing the active playlist already
                                    // activated the first remaining one.
                                    if (wasActive) restartToMain() else buildPlaylistsUi()
                                } else {
                                    Toast.makeText(
                                        this, R.string.playlist_cannot_remove_last,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: PlaylistRepository.Playlist) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(playlist.name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistRepository.renamePlaylist(this, playlist.url, input.text.toString())
                buildPlaylistsUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Add a playlist: a name (optional — the host is used when blank)
     * and an http/https M3U URL. */
    private fun showAddPlaylistDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val nameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.playlist_name_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.playlist_url_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        container.addView(nameInput)
        container.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_add_title)
            .setView(container)
            .setPositiveButton(R.string.playlist_add) { _, _ ->
                val entry = PlaylistRepository.addPlaylist(
                    this, nameInput.text.toString(), urlInput.text.toString()
                )
                if (entry == null) {
                    Toast.makeText(this, R.string.playlist_invalid_url, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.playlist_added, entry.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    buildPlaylistsUi()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Share (4.7.1, 4.8.0) ----------------------------------------------

    /** Refresh the countdown once a second while the server is running. */
    private fun startShareTicker() {
        stopShareTicker()
        val tick = object : Runnable {
            override fun run() {
                updateShareUi()
                if (PlaylistShareServer.isRunning) {
                    shareUiHandler.postDelayed(this, 1000)
                }
            }
        }
        shareTicker = tick
        shareUiHandler.post(tick)
    }

    private fun stopShareTicker() {
        shareTicker?.let { shareUiHandler.removeCallbacks(it) }
        shareTicker = null
    }

    /** Show the share URL + download URL + import URL + countdown while
     * running, else "Not sharing". */
    private fun updateShareUi() {
        val status = shareStatusView
        val button = shareButtonView
        if (PlaylistShareServer.isRunning) {
            val totalSeconds = (PlaylistShareServer.remainingMillis() / 1000).toInt()
            // Manual "m:ss" — String.format on every 1-second tick.
            val seconds = totalSeconds % 60
            val countdown = "${totalSeconds / 60}:" +
                if (seconds < 10) "0$seconds" else "$seconds"
            status.text = getString(
                R.string.share_running,
                PlaylistShareServer.shareUrl ?: "",
                PlaylistShareServer.downloadUrl ?: "",
                PlaylistShareServer.importUrl ?: "",
                countdown
            )
            button.setText(R.string.share_stop)
        } else {
            status.setText(R.string.share_not_running)
            button.setText(R.string.share_playlist)
        }
    }

    /** Let the user pick what to share: the current playlist, another
     * M3U URL, or a local M3U file. */
    private fun showShareSourceDialog() {
        val items = arrayOf(
            getString(R.string.share_source_current),
            getString(R.string.share_source_url),
            getString(R.string.share_source_file)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.share_source_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> shareCurrentPlaylist()
                    1 -> showShareUrlDialog()
                    2 -> {
                        try {
                            pickM3uFile.launch("*/*")
                        } catch (e: Exception) {
                            // Some TVs have no document picker at all.
                            Toast.makeText(this, R.string.share_no_picker, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun shareCurrentPlaylist() {
        lifecycleScope.launch {
            val result = PlaylistRepository.playlistText(this@SettingsActivity)
            result.onSuccess { text -> startSharing(text) }
                .onFailure {
                    Toast.makeText(this@SettingsActivity, R.string.share_load_failed, Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showShareUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/playlist.m3u"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.share_source_url)
            .setView(input)
            .setPositiveButton(R.string.share_start) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val result = PlaylistRepository.fetchUrlText(url)
                    result.onSuccess { text -> startSharing(text) }
                        .onFailure {
                            Toast.makeText(this@SettingsActivity, R.string.share_load_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Validate the text looks like an M3U playlist, then start the
     * temporary server (10 minutes, stops itself). */
    private fun startSharing(text: String) {
        if (!text.trimStart().startsWith("#EXTM3U")) {
            Toast.makeText(this, R.string.share_not_m3u, Toast.LENGTH_SHORT).show()
            return
        }
        if (!PlaylistShareServer.start(this, text)) {
            Toast.makeText(this, R.string.share_no_wifi, Toast.LENGTH_SHORT).show()
            return
        }
        updateShareUi()
        startShareTicker()
    }

    // ---- EPG sources (8.0.0) --------------------------------------------

    /** One row per EPG source URL. Long-press to remove it (the last
     * source can't be removed — the app always needs one). Sources
     * save immediately, like the playlists. */
    private fun buildEpgSourcesUi() {
        val container = findViewById<LinearLayout>(R.id.epg_sources_list)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        for (url in PlaylistRepository.epgUrls(this)) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            val urlView = TextView(this).apply {
                text = url
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(urlView)
            row.setOnLongClickListener {
                showRemoveEpgSourceDialog(url)
                true
            }
            container.addView(row)
        }
    }

    /** Add an EPG source: one http/https XMLTV URL. */
    private fun showAddEpgSourceDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.epg_source_url_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.epg_source_add_title)
            .setView(input)
            .setPositiveButton(R.string.epg_source_add) { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, R.string.epg_source_invalid_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val current = PlaylistRepository.epgUrls(this)
                if (current.contains(url)) {
                    Toast.makeText(this, R.string.epg_source_added, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PlaylistRepository.setEpgUrls(this, current + url)
                Toast.makeText(this, R.string.epg_source_added, Toast.LENGTH_SHORT).show()
                buildEpgSourcesUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemoveEpgSourceDialog(url: String) {
        val current = PlaylistRepository.epgUrls(this)
        if (current.size <= 1) {
            Toast.makeText(this, R.string.epg_source_cannot_remove_last, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.epg_source_remove_confirm)
            .setPositiveButton(R.string.epg_source_remove) { _, _ ->
                PlaylistRepository.setEpgUrls(this, current - url)
                Toast.makeText(this, R.string.epg_source_removed, Toast.LENGTH_SHORT).show()
                buildEpgSourcesUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Backup & restore (8.0.0) ----------------------------------------

    /** Export the backup: write opentv-backup.json to the cache dir
     * and to Downloads (so it can be pulled off the TV), then offer
     * the Android share sheet. On a TV there may be no app to share
     * with — the Downloads copy is the fallback, and the toast says
     * so. */
    private fun exportBackup() {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                BackupManager.encode(BackupManager.export(this@SettingsActivity))
            }
            val file = withContext(Dispatchers.IO) {
                try {
                    File(cacheDir, "opentv-backup.json").apply {
                        writeText(json, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (file == null) {
                Toast.makeText(this@SettingsActivity, R.string.backup_export_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val inDownloads = withContext(Dispatchers.IO) { saveBackupToDownloads(json) }
            if (inDownloads) {
                Toast.makeText(this@SettingsActivity, R.string.backup_exported, Toast.LENGTH_SHORT).show()
            }
            val uri = FileProvider.getUriForFile(
                this@SettingsActivity, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OpenTV backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, getString(R.string.backup_export)))
            } catch (e: ActivityNotFoundException) {
                if (inDownloads) {
                    Toast.makeText(this@SettingsActivity, R.string.backup_share_failed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.backup_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Copy the backup JSON into the public Downloads folder. Best
     * effort — returns false when the platform refuses (e.g. no
     * storage permission on an old Android), and the cache-dir file
     * + share sheet still work. */
    private fun saveBackupToDownloads(json: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "opentv-backup.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri: Uri? = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: return false
                true
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                File(downloads, "opentv-backup.json").apply {
                    parentFile?.mkdirs()
                    writeText(json, Charsets.UTF_8)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
