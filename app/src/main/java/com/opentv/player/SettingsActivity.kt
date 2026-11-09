package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

        val epgUrl = findViewById<EditText>(R.id.epg_url)
        val uploadUrl = findViewById<EditText>(R.id.upload_url)
        epgUrl.setText(PlaylistRepository.epgUrl(this))
        uploadUrl.setText(PlaylistRepository.uploadUrl(this))

        buildPlaylistsUi()

        shareStatusView = findViewById(R.id.share_status)
        shareButtonView = findViewById(R.id.share_button)

        findViewById<Button>(R.id.add_playlist_button).setOnClickListener {
            showAddPlaylistDialog()
        }

        findViewById<Button>(R.id.save_button).setOnClickListener {
            PlaylistRepository.setEpgUrl(this, epgUrl.text.toString().trim())
            PlaylistRepository.setUploadUrl(this, uploadUrl.text.toString().trim())
            finish()
        }

        findViewById<Button>(R.id.reset_button).setOnClickListener {
            PlaylistRepository.resetUrls(this)
            epgUrl.setText(PlaylistRepository.DEFAULT_EPG_URL)
            uploadUrl.setText("")
            buildPlaylistsUi()
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
}
