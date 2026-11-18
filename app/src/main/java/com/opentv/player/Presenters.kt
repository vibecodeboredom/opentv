package com.opentv.player

import android.content.res.ColorStateList
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.core.content.ContextCompat
import androidx.leanback.widget.ImageCardView
import androidx.leanback.widget.Presenter
import coil.load

/** Card for a channel: logo (or play icon) + name + number + group. Rounded dark card. */
class ChannelPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val channel = item as Channel
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = channel.name
        cardView.contentText = if (channel.number > 0) {
            "#${channel.number} • ${channel.group}"
        } else {
            channel.group
        }
        cardView.mainImageView.load(channel.logo) {
            // 6.1.0: downsample to the card's 320x180px image instead of
            // decoding the provider's full-resolution logo.
            size(CARD_WIDTH, CARD_HEIGHT)
            crossfade(true)
            placeholder(android.R.drawable.ic_media_play)
            error(android.R.drawable.ic_media_play)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}

/** Card for a menu action (change playlist / refresh / guide / recordings). */
class ActionPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val action = item as MenuAction
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = action.title
        cardView.contentText = action.subtitle
        val iconRes = when (action.id) {
            "settings" -> android.R.drawable.ic_menu_preferences
            "refresh" -> android.R.drawable.ic_popup_sync
            "guide" -> android.R.drawable.ic_menu_today
            "recordings" -> android.R.drawable.ic_menu_save
            "statistics" -> android.R.drawable.ic_menu_info_details
            else -> android.R.drawable.ic_menu_more
        }
        cardView.mainImage = ContextCompat.getDrawable(cardView.context, iconRes)
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}

/** Card for a local recording: channel name + size / date. */
class RecordingPresenter : Presenter() {
    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val recording = item as Recording
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = recording.channelName
        cardView.contentText = RecordingManager.formatBytes(recording.bytesDownloaded) +
            if (recording.inProgress) " • Recording…" else ""
        cardView.mainImage = ContextCompat.getDrawable(
            cardView.context,
            android.R.drawable.ic_media_play
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}

/**
 * 8.2.0 (Nova): card for the home screen's "On now" row — channel
 * logo + name on top, and underneath the programme on right now
 * with the time left in it.
 * 8.2.1 (Nova): the card gains an "ends HH:MM" label in the
 * content line and a slim progress bar along its bottom edge —
 * how far through the programme we are. The bar is an overlay
 * child of the ImageCardView, created once per card; binding
 * sets its fraction, and the home screen's 60s ticker rebinds
 * the row in place so it advances without a row rebuild.
 */
class OnNowPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        val density = parent.context.resources.displayMetrics.density
        val bar = ProgressBar(
            parent.context, null, android.R.attr.progressBarStyleHorizontal
        ).apply {
            max = PROGRESS_MAX
            progressTintList = ColorStateList.valueOf(PROGRESS_COLOR)
            progressBackgroundTintList = ColorStateList.valueOf(TRACK_COLOR)
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (3 * density).toInt().coerceAtLeast(2)
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = (12 * density).toInt()
                rightMargin = (12 * density).toInt()
                bottomMargin = (6 * density).toInt()
            }
        }
        cardView.addView(bar)
        cardView.tag = bar
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val entry = item as OnNowEntry
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = entry.channel.name
        val now = System.currentTimeMillis()
        val remaining = GuideData.remainingMinutes(entry.programme, now)
        cardView.contentText = entry.programme.title + " • " +
            cardView.context.getString(
                R.string.on_now_ends, GuideData.formatClock(entry.programme.stopMillis)
            ) + " • " +
            cardView.context.getString(R.string.on_now_remaining, "${remaining}m")
        val total = entry.programme.stopMillis - entry.programme.startMillis
        val bar = cardView.tag as? ProgressBar
        bar?.progress = if (total > 0) {
            ((now - entry.programme.startMillis).coerceIn(0, total) *
                PROGRESS_MAX / total).toInt()
        } else {
            0
        }
        cardView.mainImageView.load(entry.channel.logo) {
            size(CARD_WIDTH, CARD_HEIGHT)
            crossfade(true)
            placeholder(android.R.drawable.ic_media_play)
            error(android.R.drawable.ic_media_play)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
        (cardView.tag as? ProgressBar)?.progress = 0
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180

        /** 8.2.1 (Nova): progress bar resolution + palette — the
         * guide green on a faint white track. */
        private const val PROGRESS_MAX = 1000
        private const val PROGRESS_COLOR = 0xFF86C739.toInt()
        private const val TRACK_COLOR = 0x33FFFFFF
    }
}

/**
 * 8.3.0 (Nova): when a watchlist programme starts, in the home
 * row's words — "Today 8:00 PM", "Tomorrow 8:00 PM", or the
 * guide's own date + clock for anything further out. Shared by
 * the watchlist card and the toast a tap on a future card shows,
 * so the two never disagree.
 */
internal fun watchlistStartLabel(context: android.content.Context, startMillis: Long): String {
    val clock = GuideData.formatClock(startMillis)
    val start = java.util.Calendar.getInstance().apply { timeInMillis = startMillis }
    val today = java.util.Calendar.getInstance()
    val tomorrow = java.util.Calendar.getInstance().apply {
        add(java.util.Calendar.DAY_OF_YEAR, 1)
    }
    fun sameDay(cal: java.util.Calendar): Boolean =
        start.get(java.util.Calendar.YEAR) == cal.get(java.util.Calendar.YEAR) &&
            start.get(java.util.Calendar.DAY_OF_YEAR) == cal.get(java.util.Calendar.DAY_OF_YEAR)
    return when {
        sameDay(today) -> context.getString(R.string.watchlist_today) + " " + clock
        sameDay(tomorrow) -> context.getString(R.string.watchlist_tomorrow) + " " + clock
        else -> GuideData.formatDate(startMillis) + ", " + clock
    }
}

/**
 * 8.3.0 (Nova): card for the home screen's "Watchlist" row — a
 * programme the user set a reminder for. Programme title on top,
 * channel + start underneath ("Today 8:00 PM" style); once the
 * programme is on, the line leads with "On now" instead and the
 * tap plays the channel. Channel logo up top, like the On now
 * cards.
 */
class WatchlistPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val entry = item as WatchlistEntry
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = entry.reminder.title
        cardView.contentText = if (entry.onNow) {
            cardView.context.getString(R.string.on_now) + " • " + entry.channel.name
        } else {
            entry.channel.name + " • " +
                watchlistStartLabel(cardView.context, entry.reminder.startMillis)
        }
        cardView.mainImageView.load(entry.channel.logo) {
            size(CARD_WIDTH, CARD_HEIGHT)
            crossfade(true)
            placeholder(android.R.drawable.ic_media_play)
            error(android.R.drawable.ic_media_play)
        }
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}

/**
 * 8.2.0 (Nova): card for a scheduled recording on the recordings
 * screen — programme title on top, channel + day/time underneath.
 */
class ScheduledRecordingPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val scheduled = item as PlaylistRepository.ScheduledRecording
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = scheduled.title
        cardView.contentText = scheduled.channelName + " • " +
            GuideData.formatDate(scheduled.startMillis) + ", " +
            GuideData.formatClock(scheduled.startMillis)
        cardView.mainImage = ContextCompat.getDrawable(
            cardView.context,
            android.R.drawable.ic_menu_recent_history
        )
    }

    override fun onUnbindViewHolder(viewHolder: ViewHolder) {
        val cardView = viewHolder.view as ImageCardView
        cardView.mainImage = null
    }

    companion object {
        private const val CARD_WIDTH = 320
        private const val CARD_HEIGHT = 180
    }
}
