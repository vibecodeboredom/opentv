package com.opentv.player

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** One group row in the sidebar (key + label + channel count). */
data class SidebarGroup(val key: String, val label: String, val count: Int)

/**
 * The left sidebar's list (6.0.0-rc2). The sidebar used to inflate up
 * to 200 channel rows into a LinearLayout inside a ScrollView — ~1,200
 * views built, measured and laid out on every open and re-measured on
 * every focus move, plus 200 logo fetches fired at once. On the Onn 4K
 * box that was the lag the user reported. A RecyclerView binds only the
 * ~10 rows on screen, and Coil cancels a logo load when its row is
 * recycled, so opening the sidebar and moving through it stays fast.
 *
 * Two row types: group rows (name + count) and channel rows (number +
 * logo + name + now-playing). The row visuals are the same as the old
 * code-built rows (same paddings, sizes, colours, sidebar_row_bg) —
 * only the container changed.
 */
class SidebarAdapter(
    private val onGroupClick: (SidebarGroup) -> Unit,
    private val onChannelClick: (Channel) -> Unit,
    private val currentChannelId: () -> String?
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    /** One row of the list: a group or a channel. */
    sealed class Item {
        data class GroupItem(val group: SidebarGroup) : Item()
        data class ChannelItem(val channel: Channel) : Item()
    }

    private var items: List<Item> = emptyList()
    /** Channel id -> now-playing title, filled in when the EPG arrives. */
    private var programmeTitles: Map<String, String> = emptyMap()
    private var noInfo: String = ""

    fun submitList(newItems: List<Item>, titles: Map<String, String>, noInfoText: String) {
        items = newItems
        programmeTitles = titles
        noInfo = noInfoText
        notifyDataSetChanged()
    }

    /** Drop the now-playing titles (sidebar hidden — same as the old
     * programmeViews.clear()). */
    fun clearTitles() {
        programmeTitles = emptyMap()
    }

    /** Fill in now-playing titles after the EPG loads, notifying only
     * the rows whose title changed so focus is never disturbed. */
    fun updateProgrammeTitles(titles: Map<String, String>) {
        if (titles.isEmpty()) return
        val merged = programmeTitles.toMutableMap()
        var changed = false
        for ((id, title) in titles) {
            if (merged[id] != title) {
                merged[id] = title
                changed = true
            }
        }
        if (!changed) return
        programmeTitles = merged
        for (i in items.indices) {
            val item = items[i]
            if (item is Item.ChannelItem && titles.containsKey(item.channel.id)) {
                notifyItemChanged(i)
            }
        }
    }

    fun itemAt(position: Int): Item? = items.getOrNull(position)

    fun positionOfChannel(channelId: String): Int =
        items.indexOfFirst { it is Item.ChannelItem && it.channel.id == channelId }

    fun positionOfGroup(key: String): Int =
        items.indexOfFirst { it is Item.GroupItem && it.group.key == key }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int = when (items[position]) {
        is Item.GroupItem -> VIEW_TYPE_GROUP
        is Item.ChannelItem -> VIEW_TYPE_CHANNEL
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val density = parent.context.resources.displayMetrics.density
        return if (viewType == VIEW_TYPE_GROUP) {
            GroupViewHolder(buildGroupRow(parent.context, density), onGroupClick)
        } else {
            ChannelViewHolder(
                buildChannelRow(parent.context, density),
                onChannelClick, currentChannelId
            )
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is Item.GroupItem -> (holder as GroupViewHolder).bind(item.group)
            is Item.ChannelItem -> (holder as ChannelViewHolder).bind(
                item.channel, programmeTitles[item.channel.id] ?: noInfo
            )
        }
    }

    private fun dp(v: Int, density: Float): Int = (v * density).toInt()

    /** One group row: name + channel count, accent text when focused. */
    private fun buildGroupRow(context: Context, density: Float): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12, density), dp(12, density), dp(12, density), dp(12, density))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2, density); bottomMargin = dp(2, density) }
            minimumHeight = dp(56, density)
            setBackgroundResource(R.drawable.sidebar_row_bg)
        }
        val nameView = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countView = TextView(context).apply {
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(nameView)
        row.addView(countView)
        return row
    }

    /** One channel row: number + logo + name + now-playing programme. */
    private fun buildChannelRow(context: Context, density: Float): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(10, density), dp(10, density), dp(10, density), dp(10, density))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2, density); bottomMargin = dp(2, density) }
            minimumHeight = dp(56, density)
            setBackgroundResource(R.drawable.sidebar_row_bg)
        }
        val numberView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(32, density), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val logoView = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32, density), dp(32, density)).apply {
                marginEnd = dp(8, density)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val textBlock = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val programmeView = TextView(context).apply {
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
        return row
    }

    private class GroupViewHolder(
        view: View,
        private val onGroupClick: (SidebarGroup) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = (view as LinearLayout).getChildAt(0) as TextView
        private val countView: TextView = view.getChildAt(1) as TextView
        private var group: SidebarGroup? = null

        init {
            view.setOnClickListener { group?.let(onGroupClick) }
            view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                nameView.setTextColor(if (hasFocus) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt())
            }
        }

        fun bind(group: SidebarGroup) {
            this.group = group
            nameView.text = group.label
            countView.text = group.count.toString()
            // A rebound row keeps its focus state; re-apply the colour.
            nameView.setTextColor(
                if (itemView.isFocused) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt()
            )
        }
    }

    private class ChannelViewHolder(
        view: View,
        private val onChannelClick: (Channel) -> Unit,
        private val currentChannelId: () -> String?
    ) : RecyclerView.ViewHolder(view) {
        private val numberView: TextView = (view as LinearLayout).getChildAt(0) as TextView
        private val logoView: ImageView = view.getChildAt(1) as ImageView
        private val textBlock: LinearLayout = view.getChildAt(2) as LinearLayout
        private val nameView: TextView = textBlock.getChildAt(0) as TextView
        private val programmeView: TextView = textBlock.getChildAt(1) as TextView
        private var channel: Channel? = null
        private var isCurrent = false

        init {
            view.setOnClickListener { channel?.let(onChannelClick) }
            view.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                applyFocusColors(hasFocus)
            }
        }

        fun bind(ch: Channel, programmeTitle: String) {
            channel = ch
            isCurrent = ch.id == currentChannelId()
            numberView.text = if (ch.number > 0) ch.number.toString() else ""
            nameView.text = ch.name
            programmeView.text = programmeTitle
            logoView.load(ch.logo) {
                placeholder(R.drawable.ic_play)
                error(R.drawable.ic_play)
            }
            // A rebound row keeps its focus state; re-apply the colours.
            applyFocusColors(itemView.isFocused)
        }

        private fun applyFocusColors(hasFocus: Boolean) {
            // Focused row: name (and number, unless it is the current
            // channel, which is already accent) turns accent.
            nameView.setTextColor(
                if (hasFocus || isCurrent) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt()
            )
            numberView.setTextColor(
                if (isCurrent || hasFocus) 0xFF86C739.toInt() else 0xB3FFFFFF.toInt()
            )
        }
    }

    private companion object {
        const val VIEW_TYPE_GROUP = 0
        const val VIEW_TYPE_CHANNEL = 1
    }
}
