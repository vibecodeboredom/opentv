package com.opentv.player

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The TV guide: channels as rows, time as columns.
 *
 * A 6-hour window starting at the top of the current hour. The programme
 * airing now is highlighted green. Selecting a programme that's on now
 * starts playback; a future programme shows when it starts.
 *
 * Built with plain Views inside a horizontal + vertical scroll pair so the
 * time header and every row scroll together.
 */
class GuideActivity : FragmentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var scroll: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        container = findViewById(R.id.guide_container)
        loading = findViewById(R.id.guide_loading)
        scroll = findViewById(R.id.guide_scroll)

        lifecycleScope.launch {
            val playlistResult = PlaylistRepository.loadPlaylist(this@GuideActivity)
            val epg = PlaylistRepository.loadEpg(this@GuideActivity)
            loading.visibility = View.GONE
            playlistResult.onSuccess { channels ->
                val now = System.currentTimeMillis()
                val windowStart = GuideData.windowStartMillis(now)
                val rows = GuideData.buildRows(channels, epg, windowStart, WINDOW_HOURS)
                if (rows.isEmpty()) {
                    Toast.makeText(
                        this@GuideActivity,
                        R.string.no_guide_data,
                        Toast.LENGTH_LONG
                    ).show()
                    finish()
                    return@onSuccess
                }
                buildGuide(rows, windowStart, now)
                scroll.visibility = View.VISIBLE
            }.onFailure {
                Toast.makeText(this@GuideActivity, R.string.load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun buildGuide(
        rows: List<GuideData.GuideRow>,
        windowStart: Long,
        now: Long
    ) {
        val windowEnd = windowStart + WINDOW_HOURS * 60L * 60 * 1000
        val pxPerMinute = dpToPx(PX_PER_MINUTE_DP)
        val channelColWidth = dpToPx(CHANNEL_COL_DP)
        val cellHeight = dpToPx(CELL_HEIGHT_DP)
        val cellMargin = dpToPx(CELL_MARGIN_DP)

        // Time header: spacer over the channel column, then one label per hour.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        header.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight)
        })
        for (h in 0 until WINDOW_HOURS) {
            val label = TextView(this).apply {
                text = GuideData.formatHourLabel(windowStart + h * 60L * 60 * 1000)
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (pxPerMinute * 60).toInt(), cellHeight
                )
            }
            header.addView(label)
        }
        container.addView(header)

        // One row per channel.
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = cellMargin }
            }
            // Channel name (fixed column).
            val nameView = TextView(this).apply {
                text = row.channel.name
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight)
            }
            rowLayout.addView(nameView)

            // Programme cells, with spacers for gaps.
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
                val isNow = now in programme.startMillis until programme.stopMillis
                val cell = TextView(this).apply {
                    text = programme.title
                    setTextColor(0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dpToPx(8), 0, dpToPx(8), 0)
                    isFocusable = true
                    isFocusableInTouchMode = true
                    setBackgroundResource(
                        if (isNow) R.drawable.guide_cell_now_bg else R.drawable.guide_cell_bg
                    )
                    layoutParams = LinearLayout.LayoutParams(
                        (pxPerMinute * layout.widthMinutes).toInt() - cellMargin * 2,
                        cellHeight
                    ).apply {
                        leftMargin = cellMargin
                        rightMargin = cellMargin
                    }
                    setOnClickListener { onProgrammeClicked(row.channel, programme, now) }
                }
                rowLayout.addView(cell)
                posMinutes = layout.startOffsetMinutes + layout.widthMinutes
            }
            container.addView(rowLayout)
        }
    }

    private fun onProgrammeClicked(
        channel: Channel,
        programme: EpgParser.Programme,
        now: Long
    ) {
        if (now in programme.startMillis until programme.stopMillis) {
            val intent = Intent(this, PlaybackActivity::class.java)
            intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, channel)
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

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        private const val WINDOW_HOURS = 6
        private const val PX_PER_MINUTE_DP = 4
        private const val CHANNEL_COL_DP = 200
        private const val CELL_HEIGHT_DP = 64
        private const val CELL_MARGIN_DP = 2
    }
}
