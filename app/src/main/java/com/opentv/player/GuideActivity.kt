package com.opentv.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import coil.load
import kotlinx.coroutines.launch

/**
 * The TV guide, rebuilt from scratch (v4.0.1) to match the TiviMate
 * reference photo exactly:
 * - Big details header: preview box with the channel logo, programme
 *   title, time range, progress bar with minutes left, favorite star,
 *   and the channel's category. It follows the focused programme.
 * - Grid: channel numbers in blue, logos, and names down the left;
 *   30-minute time columns across the top with the date + current time;
 *   flat programme cells showing just the title. The focused cell turns
 *   white with dark text, and its channel name turns blue. Red
 *   current-time line.
 * - Long-press OK on a programme opens the context menu (search, settings,
 *   external player, record, custom recording, favorites, block/hide,
 *   assign EPG, channel options). Search lives in that menu, not in the
 *   guide itself.
 *
 * A 6-hour window starting at the top of the current hour.
 */
class GuideActivity : FragmentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var hscroll: HorizontalScrollView
    private lateinit var nowLine: View
    private lateinit var emptyView: TextView

    private lateinit var detailLogo: ImageView
    private lateinit var detailChannel: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailStar: TextView
    private lateinit var detailTime: TextView
    private lateinit var detailProgress: ProgressBar
    private lateinit var detailRemaining: TextView
    private lateinit var detailCategory: TextView

    private var allRows: List<GuideData.GuideRow> = emptyList()
    private var allChannels: List<Channel> = emptyList()
    private var epg: Map<String, List<EpgParser.Programme>> = emptyMap()
    private var windowStart: Long = 0L
    private var nowMillis: Long = 0L
    private var pxPerMinute: Int = 0
    private var channelColWidth: Int = 0

    /** The programme the details header is currently showing. */
    private var detailChannelShown: Channel? = null

    /** Channel name views per row, so focus can turn the name blue. */
    private val channelNameViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        container = findViewById(R.id.guide_container)
        loading = findViewById(R.id.guide_loading)
        scroll = findViewById(R.id.guide_scroll)
        hscroll = findViewById(R.id.guide_hscroll)
        nowLine = findViewById(R.id.guide_now_line)
        emptyView = findViewById(R.id.guide_empty)

        detailLogo = findViewById(R.id.guide_detail_logo)
        detailChannel = findViewById(R.id.guide_detail_channel)
        detailTitle = findViewById(R.id.guide_detail_title)
        detailStar = findViewById(R.id.guide_detail_star)
        detailTime = findViewById(R.id.guide_detail_time)
        detailProgress = findViewById(R.id.guide_detail_progress)
        detailRemaining = findViewById(R.id.guide_detail_remaining)
        detailCategory = findViewById(R.id.guide_detail_category)

        pxPerMinute = dpToPx(PX_PER_MINUTE_DP)
        channelColWidth = dpToPx(CHANNEL_COL_DP)

        detailStar.setOnClickListener {
            val ch = detailChannelShown ?: return@setOnClickListener
            PlaylistRepository.toggleFavorite(this, ch.id)
            updateDetailStar(ch)
            val added = PlaylistRepository.favorites(this).contains(ch.id)
            Toast.makeText(
                this,
                if (added) R.string.menu_added_favorite else R.string.menu_removed_favorite,
                Toast.LENGTH_SHORT
            ).show()
        }

        lifecycleScope.launch {
            val playlistResult = PlaylistRepository.loadPlaylist(this@GuideActivity)
            epg = PlaylistRepository.loadEpg(this@GuideActivity)
            loading.visibility = View.GONE
            playlistResult.onSuccess { channels ->
                nowMillis = System.currentTimeMillis()
                windowStart = GuideData.windowStartMillis(nowMillis)
                allChannels = channels
                rebuildRows()
                if (allRows.isEmpty()) {
                    Toast.makeText(
                        this@GuideActivity,
                        R.string.no_guide_data,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@onSuccess
                }
                buildGuide(allRows)
                scroll.visibility = View.VISIBLE
                // Don't auto-scroll: the channel column (numbers + names)
                // stays pinned at the left, like the reference photo.
                // The red now-line marks the current time; the user scrolls
                // right to see later programmes.
            }.onFailure {
                Toast.makeText(this@GuideActivity, R.string.load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun rebuildRows() {
        allRows = GuideData.buildRows(
            allChannels, epg, windowStart, WINDOW_HOURS,
            hidden = PlaylistRepository.hiddenChannels(this),
            epgOverrides = PlaylistRepository.epgOverrides(this)
        )
    }

    private fun applySearch(query: String) {
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            buildGuide(allRows)
            emptyView.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            return
        }
        val filtered = allRows.filter { row ->
            row.channel.name.lowercase().contains(q) ||
                row.programmes.any { it.title.lowercase().contains(q) }
        }
        if (filtered.isEmpty()) {
            container.removeAllViews()
            scroll.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            buildGuide(filtered)
        }
    }

    private fun scrollToNow() {
        val nowOffsetMinutes = (nowMillis - windowStart) / 60_000L
        val targetX = channelColWidth + (nowOffsetMinutes * pxPerMinute).toInt()
        hscroll.post { hscroll.smoothScrollTo(maxOf(targetX - dpToPx(120), 0), 0) }
    }

    /** Show a channel + programme in the details header. */
    private fun showDetails(channel: Channel, programme: EpgParser.Programme) {
        detailChannelShown = channel
        detailTitle.text = programme.title
        detailTime.text = GuideData.formatTimeRange(programme.startMillis, programme.stopMillis)
        detailChannel.text = if (channel.number > 0) {
            "${channel.number}  ${channel.name}"
        } else {
            channel.name
        }
        detailCategory.text = channel.group
        detailLogo.load(channel.logo) {
            placeholder(android.R.drawable.ic_media_play)
            error(android.R.drawable.ic_media_play)
        }
        val isNow = nowMillis in programme.startMillis until programme.stopMillis
        if (isNow) {
            detailProgress.progress =
                (GuideData.progressFraction(programme, nowMillis) * 100).toInt()
            detailRemaining.text = getString(
                R.string.guide_min_left,
                GuideData.remainingMinutes(programme, nowMillis)
            )
            detailProgress.visibility = View.VISIBLE
            detailRemaining.visibility = View.VISIBLE
        } else {
            detailProgress.visibility = View.INVISIBLE
            detailRemaining.visibility = View.INVISIBLE
        }
        updateDetailStar(channel)
    }

    private fun updateDetailStar(channel: Channel) {
        val isFav = PlaylistRepository.favorites(this).contains(channel.id)
        detailStar.text = if (isFav) "★" else "☆"
        detailStar.setTextColor(
            if (isFav) 0xFFFFC107.toInt() else 0xFF7A7A85.toInt()
        )
    }

    private fun buildGuide(rows: List<GuideData.GuideRow>) {
        container.removeAllViews()
        channelNameViews.clear()
        nowLine.visibility = View.GONE
        val windowEnd = windowStart + WINDOW_HOURS * 60L * 60 * 1000
        val cellHeight = dpToPx(CELL_HEIGHT_DP)
        val cellMargin = dpToPx(CELL_MARGIN_DP)

        // Time header: date + current time, then one label per 30 minutes.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF121218.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(TextView(this).apply {
            text = GuideData.formatDateTime(nowMillis)
            setTextColor(0xFFC9C9D1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(12), 0, dpToPx(12), 0)
            layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight)
        })
        for (m in 0 until WINDOW_HOURS * 60 step 30) {
            val label = TextView(this).apply {
                text = GuideData.formatHourLabel(windowStart + m * 60L * 1000)
                setTextColor(0xFF9A9AA3.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (pxPerMinute * 30).toInt(), cellHeight
                )
            }
            header.addView(label)
        }
        container.addView(header)

        // One row per channel: number + logo + name, then programme cells.
        var firstNow: Pair<Channel, EpgParser.Programme>? = null
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = cellMargin }
            }

            // Channel cell: number + logo + name (no group line, like the photo).
            val channelCell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF121218.toInt())
                setPadding(dpToPx(12), 0, dpToPx(12), 0)
                layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight)
            }
            if (row.channel.number > 0) {
                channelCell.addView(TextView(this).apply {
                    text = row.channel.number.toString()
                    setTextColor(0xFF3B82F6.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
                    typeface = Typeface.DEFAULT_BOLD
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        dpToPx(48),
                        LinearLayout.LayoutParams.MATCH_PARENT
                    )
                })
            }
            val logo = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(48), dpToPx(48)).apply {
                    marginEnd = dpToPx(12)
                }
                load(row.channel.logo) {
                    placeholder(android.R.drawable.ic_media_play)
                    error(android.R.drawable.ic_media_play)
                }
            }
            channelCell.addView(logo)
            val nameView = TextView(this).apply {
                text = row.channel.name
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            channelCell.addView(nameView)
            channelNameViews[row.channel.id] = nameView
            rowLayout.addView(channelCell)

            // Programme cells (title only, like the photo), with spacers for gaps.
            var posMinutes = 0L
            for (programme in row.programmes) {
                val layout = GuideData.layoutCell(programme, windowStart, windowEnd)
                if (layout.startOffsetMinutes > posMinutes) {
                    rowLayout.addView(View(this).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            (pxPerMinute * (layout.startOffsetMinutes - posMinutes)).toInt(),
                            cellHeight
                        )
                    })
                }
                val isNow = nowMillis in programme.startMillis until programme.stopMillis
                val isPast = programme.stopMillis <= nowMillis
                if (isNow && firstNow == null) firstNow = row.channel to programme
                val card = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8))
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundResource(
                        when {
                            isNow -> R.drawable.guide_cell_now_bg
                            isPast -> R.drawable.guide_cell_past_bg
                            else -> R.drawable.guide_cell_bg
                        }
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        (pxPerMinute * layout.widthMinutes).toInt() - cellMargin * 2,
                        cellHeight
                    ).apply {
                        leftMargin = cellMargin
                        rightMargin = cellMargin
                    }
                }
                val titleView = TextView(this).apply {
                    text = programme.title
                    setTextColor(if (isPast) 0xFF6B6B75.toInt() else 0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                card.addView(titleView)
                card.setOnClickListener { onProgrammeClicked(row.channel, programme) }
                card.setOnLongClickListener {
                    showContextMenu(row.channel, programme)
                    true
                }
                card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        // Focused cell turns white with dark text; its
                        // channel name turns blue (like the photo).
                        titleView.setTextColor(0xFF121218.toInt())
                        nameView.setTextColor(0xFF3B82F6.toInt())
                        showDetails(row.channel, programme)
                    } else {
                        titleView.setTextColor(
                            if (isPast) 0xFF6B6B75.toInt() else 0xFFFFFFFF.toInt()
                        )
                        nameView.setTextColor(0xFFFFFFFF.toInt())
                    }
                }
                rowLayout.addView(card)
                posMinutes = layout.startOffsetMinutes + layout.widthMinutes
            }
            container.addView(rowLayout)
        }

        // Show the first programme on now in the details header.
        firstNow?.let { (ch, prog) -> showDetails(ch, prog) }

        // Position the red now-line at the current time, spanning all rows.
        val nowOffsetMinutes = (nowMillis - windowStart) / 60_000L
        val nowX = channelColWidth + (nowOffsetMinutes * pxPerMinute).toInt()
        container.post {
            val frame = findViewById<FrameLayout>(R.id.guide_frame)
            nowLine.translationX = nowX.toFloat()
            nowLine.layoutParams = FrameLayout.LayoutParams(
                dpToPx(3),
                frame.height
            )
            nowLine.visibility = View.VISIBLE
            nowLine.requestLayout()
        }
    }

    private fun onProgrammeClicked(channel: Channel, programme: EpgParser.Programme) {
        if (nowMillis in programme.startMillis until programme.stopMillis) {
            // CLEAR_TOP replaces the playback underneath instead of
            // stacking a second player on top of it.
            val intent = Intent(this, PlaybackActivity::class.java)
            intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, channel)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } else {
            val whenText = GuideData.formatHourLabel(programme.startMillis)
            Toast.makeText(
                this,
                "${programme.title} — ${getString(R.string.starts_at, whenText)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * The context menu (long-press OK on a programme), TiviMate style:
     * a rounded panel with search, settings, and programme/channel actions.
     */
    private fun showContextMenu(channel: Channel, programme: EpgParser.Programme) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val view = layoutInflater.inflate(R.layout.dialog_guide_menu, null)
        dialog.setContentView(view)
        dialog.window?.setLayout(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

        val menuSearch = view.findViewById<EditText>(R.id.menu_search)
        menuSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearch(s?.toString().orEmpty())
            }
        })
        menuSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                dialog.dismiss()
                true
            } else false
        }

        view.findViewById<TextView>(R.id.menu_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        view.findViewById<TextView>(R.id.menu_programme_title).text = programme.title

        view.findViewById<TextView>(R.id.menu_external_player).setOnClickListener {
            dialog.dismiss()
            openExternalPlayer(channel)
        }

        val recordRow = view.findViewById<TextView>(R.id.menu_record)
        val isRecording = RecordingManager.isRecording(this, channel.id)
        recordRow.text = getString(
            if (isRecording) R.string.menu_stop_recording else R.string.menu_record
        )
        recordRow.setOnClickListener {
            dialog.dismiss()
            if (RecordingManager.isRecording(this, channel.id)) {
                RecordingManager.stopRecording(this, channel.id)
                Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_SHORT).show()
            } else {
                RecordingManager.startRecording(this, channel)
                Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<TextView>(R.id.menu_custom_recording).setOnClickListener {
            dialog.dismiss()
            showCustomRecordingPicker(channel)
        }

        view.findViewById<TextView>(R.id.menu_channel_name).text =
            if (channel.number > 0) "${channel.number}  ${channel.name}" else channel.name

        val favoriteRow = view.findViewById<TextView>(R.id.menu_favorite)
        val isFav = PlaylistRepository.favorites(this).contains(channel.id)
        favoriteRow.text = getString(
            if (isFav) R.string.menu_remove_favorite else R.string.menu_add_favorite
        )
        favoriteRow.setOnClickListener {
            dialog.dismiss()
            PlaylistRepository.toggleFavorite(this, channel.id)
            val added = PlaylistRepository.favorites(this).contains(channel.id)
            Toast.makeText(
                this,
                if (added) R.string.menu_added_favorite else R.string.menu_removed_favorite,
                Toast.LENGTH_SHORT
            ).show()
            detailChannelShown?.let { updateDetailStar(it) }
        }

        view.findViewById<TextView>(R.id.menu_block).setOnClickListener {
            dialog.dismiss()
            PlaylistRepository.hideChannel(this, channel.id)
            Toast.makeText(this, R.string.menu_channel_blocked, Toast.LENGTH_SHORT).show()
            rebuildRows()
            buildGuide(allRows)
        }

        view.findViewById<TextView>(R.id.menu_hide).setOnClickListener {
            dialog.dismiss()
            PlaylistRepository.hideChannel(this, channel.id)
            Toast.makeText(this, R.string.menu_channel_hidden, Toast.LENGTH_SHORT).show()
            rebuildRows()
            buildGuide(allRows)
        }

        view.findViewById<TextView>(R.id.menu_assign_epg).setOnClickListener {
            dialog.dismiss()
            showAssignEpgDialog(channel)
        }

        view.findViewById<TextView>(R.id.menu_channel_options).setOnClickListener {
            dialog.dismiss()
            showChannelOptions(channel)
        }

        dialog.show()
    }

    private fun openExternalPlayer(channel: Channel) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(channel.url), "video/*")
            }
            startActivity(Intent.createChooser(intent, channel.name))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.menu_no_external_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomRecordingPicker(channel: Channel) {
        val minutes = intArrayOf(15, 30, 60, 120)
        val labels = minutes.map { "$it min" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_recording_duration)
            .setItems(labels) { _, which ->
                val duration = minutes[which]
                RecordingManager.startRecording(this, channel)
                RecordingManager.scheduleStopRecording(
                    this, channel.id,
                    System.currentTimeMillis() + duration * 60_000L
                )
                Toast.makeText(
                    this,
                    getString(R.string.menu_recording_for, duration),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAssignEpgDialog(channel: Channel) {
        val input = EditText(this).apply {
            setText(PlaylistRepository.epgOverrides(this@GuideActivity)[channel.id]
                ?: channel.tvgId.orEmpty())
            hint = getString(R.string.menu_assign_epg_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_assign_epg_title, channel.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistRepository.setEpgOverride(this, channel.id, input.text.toString())
                Toast.makeText(this, R.string.menu_epg_assigned, Toast.LENGTH_SHORT).show()
                rebuildRows()
                buildGuide(allRows)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChannelOptions(channel: Channel) {
        val details = getString(
            R.string.menu_channel_details,
            channel.number,
            channel.name,
            channel.group,
            channel.tvgId ?: "—"
        )
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val WINDOW_HOURS = 6
        private const val PX_PER_MINUTE_DP = 4
        private const val CHANNEL_COL_DP = 320
        private const val CELL_HEIGHT_DP = 72
        private const val CELL_MARGIN_DP = 3
    }
}
