package com.opentv.player

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Touch-first home screen (6.0.0-rc1, first mobile-capable release).
 * Phones and tablets get this instead of the Leanback browse menu:
 * a search field, quick actions (Guide / Recordings / Settings /
 * Refresh), a "Continue watching" card for the last channel that
 * actually played, and the channel list grouped by category
 * (Favorites first). Tapping a channel plays it; tapping its star
 * toggles the favorite. Long lists are a RecyclerView, so all 1500
 * channels are reachable — the sidebar's 200-row cap does not apply
 * here. The TV never sees this screen (MainActivity routes TV to
 * auto-play, as before).
 */
class MobileHomeActivity : FragmentActivity() {

    private companion object {
        /** Search waits this long after the last keystroke before
         * rebuilding the list (6.1.0) — typing used to rebuild the
         * whole ~1500-item list on every keystroke. */
        private const val SEARCH_DEBOUNCE_MS = 150L
    }

    private lateinit var recycler: RecyclerView
    private lateinit var search: EditText
    private lateinit var loading: ProgressBar
    private lateinit var emptyView: LinearLayout
    private lateinit var emptyText: TextView
    private lateinit var retryButton: Button
    private lateinit var continueCard: LinearLayout
    private lateinit var continueName: TextView

    private val adapter = MobileChannelAdapter(
        onChannelClick = { playChannel(it) },
        onFavoriteToggle = { toggleFavorite(it) }
    )

    private var allChannels: List<Channel> = emptyList()
    private var favorites: Set<String> = emptySet()
    /** Favorites as last rendered, so onResume only rebuilds when
     * something actually changed (e.g. favorited from playback) —
     * a rebuild on every return would reset the scroll position. */
    private var lastRenderedFavorites: Set<String> = emptySet()
    private var lastChannel: Channel? = null
    private var currentQuery: String = ""
    /** 6.1.0: debounced search — renderList builds a ~1500-item
     * list, and the old code did it on every keystroke while typing. */
    private var searchJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_mobile_home)

        recycler = findViewById(R.id.mobile_channel_list)
        search = findViewById(R.id.mobile_search)
        loading = findViewById(R.id.mobile_loading)
        emptyView = findViewById(R.id.mobile_empty)
        emptyText = findViewById(R.id.mobile_empty_text)
        retryButton = findViewById(R.id.mobile_retry_button)
        continueCard = findViewById(R.id.mobile_continue_card)
        continueName = findViewById(R.id.mobile_continue_name)

        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                // 6.1.0: debounced like the guide search — the scan
                // only runs once typing pauses, so a fast typist
                // doesn't queue a full list rebuild per keystroke.
                // Clearing the search is still instant.
                val query = s?.toString().orEmpty()
                searchJob?.cancel()
                if (query == currentQuery) return
                if (query.trim().isEmpty()) {
                    currentQuery = query
                    renderList()
                    return
                }
                searchJob = lifecycleScope.launch {
                    delay(SEARCH_DEBOUNCE_MS)
                    if (query != currentQuery) {
                        currentQuery = query
                        renderList()
                    }
                }
            }
        })

        findViewById<Button>(R.id.mobile_guide_button).setOnClickListener {
            startActivity(Intent(this, GuideActivity::class.java))
        }
        findViewById<Button>(R.id.mobile_recordings_button).setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java))
        }
        findViewById<Button>(R.id.mobile_settings_button).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.mobile_refresh_button).setOnClickListener {
            loadPlaylist()
        }
        retryButton.setOnClickListener { loadPlaylist() }
        continueCard.setOnClickListener {
            lastChannel?.let { playChannel(it) }
        }

        loadPlaylist()
    }

    override fun onResume() {
        super.onResume()
        // A favorite may have changed while in playback — rebuild
        // only when the set actually changed, so the scroll position
        // survives a plain return.
        val current = PlaylistRepository.favorites(this)
        if (current != lastRenderedFavorites && allChannels.isNotEmpty()) {
            favorites = current
            renderList()
        }
    }

    private fun loadPlaylist() {
        loading.visibility = View.VISIBLE
        recycler.visibility = View.GONE
        emptyView.visibility = View.GONE
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@MobileHomeActivity)
            loading.visibility = View.GONE
            result.onSuccess { channels ->
                val hidden = PlaylistRepository.hiddenChannels(this@MobileHomeActivity)
                allChannels = channels.filter { it.id !in hidden }
                favorites = PlaylistRepository.favorites(this@MobileHomeActivity)
                val lastId = PlaylistRepository.lastChannelId(this@MobileHomeActivity)
                lastChannel = allChannels.firstOrNull { it.id == lastId }
                updateContinueCard()
                renderList()
            }.onFailure {
                recycler.visibility = View.GONE
                emptyText.setText(R.string.load_failed)
                emptyView.visibility = View.VISIBLE
            }
        }
    }

    private fun updateContinueCard() {
        val last = lastChannel
        if (last == null) {
            continueCard.visibility = View.GONE
        } else {
            continueName.text = if (last.number > 0) "${last.number}  ${last.name}" else last.name
            continueCard.visibility = View.VISIBLE
        }
    }

    /** Build the sectioned list for the current query: Favorites
     * first, then one section per group in playlist order (the
     * LinkedHashMap keeps first-occurrence order, like the TV
     * browse menu). A non-empty query shows a single "Results"
     * section of name/group matches instead. */
    private fun renderList() {
        val query = currentQuery.trim()
        val items = mutableListOf<MobileChannelAdapter.Item>()
        if (query.isEmpty()) {
            val favoriteChannels = allChannels.filter { it.id in favorites }
            if (favoriteChannels.isNotEmpty()) {
                items += MobileChannelAdapter.Item.Header(getString(R.string.favorites))
                favoriteChannels.forEach {
                    items += MobileChannelAdapter.Item.ChannelItem(it, true)
                }
            }
            val grouped = linkedMapOf<String, MutableList<Channel>>()
            for (ch in allChannels) {
                (grouped.getOrPut(ch.group) { mutableListOf() }) += ch
            }
            for ((group, groupChannels) in grouped) {
                items += MobileChannelAdapter.Item.Header(group)
                groupChannels.forEach {
                    items += MobileChannelAdapter.Item.ChannelItem(it, it.id in favorites)
                }
            }
        } else {
            val matches = allChannels.filter {
                it.name.contains(query, ignoreCase = true) ||
                    it.group.contains(query, ignoreCase = true)
            }
            if (matches.isNotEmpty()) {
                items += MobileChannelAdapter.Item.Header(
                    getString(R.string.mobile_results, matches.size)
                )
                matches.forEach {
                    items += MobileChannelAdapter.Item.ChannelItem(it, it.id in favorites)
                }
            }
        }
        lastRenderedFavorites = favorites
        adapter.submitList(items)
        if (items.isEmpty()) {
            recycler.visibility = View.GONE
            emptyText.setText(R.string.guide_no_results)
            emptyView.visibility = View.VISIBLE
        } else {
            emptyView.visibility = View.GONE
            recycler.visibility = View.VISIBLE
        }
    }

    private fun playChannel(channel: Channel) {
        // No CLEAR_TOP: the home list stays in the back stack, so
        // Back from playback returns here (the TV's auto-play flow
        // does not apply on mobile).
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, channel)
        startActivity(intent)
    }

    private fun toggleFavorite(channel: Channel) {
        PlaylistRepository.toggleFavorite(this, channel.id)
        favorites = PlaylistRepository.favorites(this)
        val isFav = channel.id in favorites
        adapter.updateFavorite(channel.id, isFav)
        Toast.makeText(
            this,
            if (isFav) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
            Toast.LENGTH_SHORT
        ).show()
    }
}
