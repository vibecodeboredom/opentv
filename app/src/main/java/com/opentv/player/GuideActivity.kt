package com.opentv.player

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
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
 * The TV guide, built to look like a real TV guide (TiviMate / cable):
 * channels down the left with logos, time across the top, programme cards
 * in a timeline grid, a red current-time line, and a search bar.
 *
 * A 6-hour window starting at the top of the current hour. The show on now
 * is highlighted blue. Pick a programme that's on now to start watching;
 * a future programme shows when it starts. Search filters channels and
 * programme titles. "Now" jumps the grid to the current time.
 */
class GuideActivity : FragmentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var scroll: ScrollView
    private lateinit var hscroll: HorizontalScrollView
    private lateinit var nowLine: View
    private lateinit var emptyView: TextView
    private lateinit var searchBox: EditText

    private var allRows: List<GuideData.GuideRow> = emptyList()
    private var windowStart: Long = 0L
    private var nowMillis: Long = 0L
    private var pxPerMinute: Int = 0
    private var channelColWidth: Int = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_guide)
        container = findViewById(R.id.guide_container)
        loading = findViewById(R.id.guide_loading)
        scroll = findViewById(R.id.guide_scroll)
        hscroll = findViewById(R.id.guide_hscroll)
        nowLine = findViewById(R.id.guide_now_line)
        emptyView = findViewById(R.id.guide_empty)
        searchBox = findViewById(R.id.guide_search)

        pxPerMinute = dpToPx(PX_PER_MINUTE_DP)
        channelColWidth = dpToPx(CHANNEL_COL_DP)

        findViewById<TextView>(R.id.guide_date).text =
            GuideData.formatDate(System.currentTimeMillis())
        findViewById<Button>(R.id.guide_now_button).setOnClickListener { scrollToNow() }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearch(s?.toString().orEmpty())
            }
        })

        lifecycleScope.launch {
            val playlistResult = PlaylistRepository.loadPlaylist(this@GuideActivity)
            val epg = PlaylistRepository.loadEpg(this@GuideActivity)
            loading.visibility = View.GONE
            playlistResult.onSuccess { channels ->
                nowMillis = System.currentTimeMillis()
                windowStart = GuideData.windowStartMillis(nowMillis)
                allRows = GuideData.buildRows(channels, epg, windowStart, WINDOW_HOURS)
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
                scrollToNow()
            }.onFailure {
                Toast.makeText(this@GuideActivity, R.string.load_failed, Toast.LENGTH_LONG).show()
                finish()
            }
        }
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

    private fun buildGuide(rows: List<GuideData.GuideRow>) {
        container.removeAllViews()
        nowLine.visibility = View.GONE
        val windowEnd = windowStart + WINDOW_HOURS * 60L * 60 * 1000
        val cellHeight = dpToPx(CELL_HEIGHT_DP)
        val cellMargin = dpToPx(CELL_MARGIN_DP)

        // Time header: channel column spacer + one label per hour.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF121218.toInt())
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
                setTextColor(0xFF9A9AA3.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (pxPerMinute * 60).toInt(), cellHeight
                )
            }
            header.addView(label)
        }
        container.addView(header)

        // One row per channel: logo + name, then programme cards.
        for (row in rows) {
            val rowLayout = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = cellMargin }
            }

            // Channel cell: logo + name + group.
            val channelCell = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(0xFF121218.toInt())
                setPadding(dpToPx(12), 0, dpToPx(12), 0)
                layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight)
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
            val nameCol = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            nameCol.addView(TextView(this).apply {
                text = row.channel.name
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            nameCol.addView(TextView(this).apply {
                text = row.channel.group
                setTextColor(0xFF9A9AA3.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            channelCell.addView(nameCol)
            rowLayout.addView(channelCell)

            // Programme cards, with spacers for gaps.
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
                    setOnClickListener { onProgrammeClicked(row.channel, programme) }
                }
                card.addView(TextView(this).apply {
                    text = programme.title
                    setTextColor(if (isPast) 0xFF6B6B75.toInt() else 0xFFFFFFFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                })
                card.addView(TextView(this).apply {
                    text = GuideData.formatTimeRange(programme.startMillis, programme.stopMillis)
                    setTextColor(if (isPast) 0xFF55555F.toInt() else 0xFFC9C9D1.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                })
                rowLayout.addView(card)
                posMinutes = layout.startOffsetMinutes + layout.widthMinutes
            }
            container.addView(rowLayout)
        }

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
        private const val CHANNEL_COL_DP = 280
        private const val CELL_HEIGHT_DP = 72
        private const val CELL_MARGIN_DP = 3
    }
}
