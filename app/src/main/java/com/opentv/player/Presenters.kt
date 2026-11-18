package com.opentv.player

import android.view.ViewGroup
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
 */
class OnNowPresenter : Presenter() {

    override fun onCreateViewHolder(parent: ViewGroup): ViewHolder {
        val cardView = ImageCardView(parent.context)
        cardView.isFocusable = true
        cardView.isFocusableInTouchMode = true
        cardView.setMainImageDimensions(CARD_WIDTH, CARD_HEIGHT)
        cardView.setBackgroundResource(R.drawable.card_rounded_bg)
        return ViewHolder(cardView)
    }

    override fun onBindViewHolder(viewHolder: ViewHolder, item: Any) {
        val entry = item as OnNowEntry
        val cardView = viewHolder.view as ImageCardView
        cardView.titleText = entry.channel.name
        val remaining = GuideData.remainingMinutes(
            entry.programme, System.currentTimeMillis()
        )
        cardView.contentText = entry.programme.title + " • " +
            cardView.context.getString(R.string.on_now_remaining, "${remaining}m")
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
