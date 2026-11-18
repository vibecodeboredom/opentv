package com.opentv.player

import android.content.Context

/**
 * 9.2.1 (Nova): the "More of what you watch" discovery
 * selection, shared by the TV home row (9.2.0, BrowseFragment)
 * and the mobile home section (9.2.1, MobileHomeActivity) so
 * both surfaces recommend exactly the same channels.
 *
 * Discovery from the watch history the app already keeps:
 * StatsManager's per-channel watch time (read-only here; no
 * new tracking) is totalled per channel GROUP over the
 * channels still in the playlist, the two heaviest groups
 * win, and their channels the user has NOT watched recently
 * (the Recently watched list) become the entries, each paired
 * with what's on it right now. Too little history (fewer than
 * three watched channels in the playlist) or no unwatched
 * channel in those groups yields an empty list — a discovery
 * row with nothing to discover is just noise, so callers hide
 * the row/section entirely.
 */
object Discovery {

    /** The discovery set's cap — a row/section, not a catalogue. */
    const val MAX_ENTRIES = 12

    fun moreEntries(
        context: Context,
        channels: List<Channel>,
        epgMap: Map<String, List<EpgParser.Programme>>
    ): List<OnNowEntry> {
        if (channels.isEmpty()) return emptyList()
        val hidden = PlaylistRepository.hiddenChannels(context)
        val visibleById = HashMap<String, Channel>()
        for (ch in channels) {
            if (ch.id !in hidden) visibleById[ch.id] = ch
        }
        val watched = StatsManager.getStats(context).topChannels
            .mapNotNull { stat -> visibleById[stat.id]?.let { it to stat.watchMillis } }
        if (watched.size < 3) return emptyList()
        val groupWatch = HashMap<String, Long>()
        for ((channel, millis) in watched) {
            groupWatch[channel.group] = (groupWatch[channel.group] ?: 0L) + millis
        }
        // Heaviest groups first; the sort is stable, so equal
        // totals keep first-watched order.
        val topGroups = groupWatch.entries
            .sortedByDescending { it.value }
            .take(2)
            .map { it.key }
            .toSet()
        if (topGroups.isEmpty()) return emptyList()
        val recentIds = PlaylistRepository.recentChannelIds(context).toSet()
        val overrides = PlaylistRepository.epgOverrides(context)
        val now = System.currentTimeMillis()
        val entries = mutableListOf<OnNowEntry>()
        for (channel in channels) {
            if (entries.size >= MAX_ENTRIES) break
            if (channel.id in hidden || channel.id in recentIds) continue
            if (channel.group !in topGroups) continue
            val current = GuideData.programmesForChannel(channel, epgMap, overrides)
                .firstOrNull { now in it.startMillis until it.stopMillis }
            if (current != null && current.title != GuideData.NO_INFORMATION) {
                entries += OnNowEntry(channel, current)
            }
        }
        return entries
    }
}
