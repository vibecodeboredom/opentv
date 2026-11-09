package com.opentv.player

import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * The mobile home list (6.0.0-rc1): a flat list of section headers
 * ("Favorites", then one per playlist group) and channel rows.
 * Tapping a row plays the channel; tapping its star toggles the
 * favorite without playing. Headers are built in code; rows come
 * from item_mobile_channel.xml.
 */
class MobileChannelAdapter(
    private val onChannelClick: (Channel) -> Unit,
    private val onFavoriteToggle: (Channel) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** One row of the list: a section header or a channel. */
    sealed class Item {
        data class Header(val title: String) : Item()
        data class ChannelItem(val channel: Channel, val isFavorite: Boolean) : Item()
    }

    private var items: List<Item> = emptyList()

    fun submitList(newItems: List<Item>) {
        items = newItems
        notifyDataSetChanged()
    }

    /** Flip a channel's star after a favorite toggle, without
     * rebuilding the whole list. A favorited channel appears twice
     * (Favorites section + its group section), so every matching
     * row is updated. No-op when the channel isn't shown. */
    fun updateFavorite(channelId: String, isFavorite: Boolean) {
        val updated = items.toMutableList()
        var changed = false
        for (i in updated.indices) {
            val item = updated[i]
            if (item is Item.ChannelItem && item.channel.id == channelId &&
                item.isFavorite != isFavorite
            ) {
                updated[i] = item.copy(isFavorite = isFavorite)
                changed = true
            }
        }
        if (changed) {
            items = updated
            notifyDataSetChanged()
        }
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.Header -> VIEW_TYPE_HEADER
        is Item.ChannelItem -> VIEW_TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_HEADER) {
            val view = TextView(parent.context).apply {
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(0xFF86C739.toInt())
                val pad = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics
                ).toInt()
                val padTop = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics
                ).toInt()
                setPadding(pad, padTop, pad, pad / 2)
            }
            HeaderViewHolder(view)
        } else {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_mobile_channel, parent, false)
            ChannelViewHolder(view, onChannelClick, onFavoriteToggle)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.Header -> (holder as HeaderViewHolder).bind(item.title)
            is Item.ChannelItem -> (holder as ChannelViewHolder).bind(item)
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val textView = view as TextView
        fun bind(title: String) {
            textView.text = title
        }
    }

    private class ChannelViewHolder(
        view: View,
        private val onChannelClick: (Channel) -> Unit,
        private val onFavoriteToggle: (Channel) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val logo: ImageView = view.findViewById(R.id.mobile_row_logo)
        private val name: TextView = view.findViewById(R.id.mobile_row_name)
        private val group: TextView = view.findViewById(R.id.mobile_row_group)
        private val star: TextView = view.findViewById(R.id.mobile_row_star)
        private var channel: Channel? = null

        init {
            // Rows are focusable so a remote (DPAD) works too — a plain
            // Android box without Leanback gets this screen, and its
            // only input is a remote.
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            view.setOnClickListener { channel?.let(onChannelClick) }
            star.setOnClickListener {
                channel?.let(onFavoriteToggle)
            }
        }

        fun bind(item: Item.ChannelItem) {
            channel = item.channel
            val ch = item.channel
            // 6.1.0: the channel number is accent green, the name stays
            // white — same hierarchy as the TV sidebar rows, which
            // colour the number separately.
            name.text = if (ch.number > 0) {
                val num = ch.number.toString()
                SpannableString("$num  ${ch.name}").apply {
                    setSpan(
                        ForegroundColorSpan(0xFF86C739.toInt()),
                        0, num.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            } else {
                ch.name
            }
            group.text = ch.group
            // 6.1.0: downsampled to the 48dp row logo.
            logo.loadLogo(ch.logo, 48)
            star.text = if (item.isFavorite) "★" else "☆"
            star.setTextColor(
                if (item.isFavorite) 0xFFFFC107.toInt() else 0xFF7A7A85.toInt()
            )
        }
    }

    private companion object {
        const val VIEW_TYPE_HEADER = 0
        const val VIEW_TYPE_CHANNEL = 1
    }
}
