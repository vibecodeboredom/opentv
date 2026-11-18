package com.opentv.player

/**
 * 9.0.0 — TV home screen: the shared home rails. The TV home and
 * the mobile home build their sections from this one builder, so
 * the two stay in sync by construction — same favorites first,
 * same effective groups (renames, moves and custom order applied),
 * same hidden-channel filtering.
 *
 * One rail per section: Favorites (when there is at least one),
 * Recent channels (when there is at least one, in recorded order —
 * most recent first), then one rail per effective group in display
 * order. A channel can appear in more than one rail — a favorite
 * shows in Favorites and in its group rail, just like the old
 * browse menu and the mobile home already did.
 */
object HomeRails {

    /** One home rail: its header and its channels, in rail order. */
    data class Rail(val title: String, val channels: List<Channel>)

    /**
     * Build the rails for a playlist. [recentIds] are the recorded
     * recent channel ids, most recent first (see
     * PlaylistRepository.recentChannelIds); pass an empty list when
     * the screen has no Recent rail (the mobile home keeps its
     * Continue card instead). Hidden channels are excluded from
     * every rail.
     */
    fun buildRails(
        channels: List<Channel>,
        favorites: Set<String>,
        recentIds: List<String>,
        hidden: Set<String>,
        overrides: GroupOverrides,
        favoritesTitle: String,
        recentsTitle: String
    ): List<Rail> {
        val visible = channels.filter { it.id !in hidden }
        val rails = mutableListOf<Rail>()

        val favoriteChannels = visible.filter { it.id in favorites }
        if (favoriteChannels.isNotEmpty()) {
            rails += Rail(favoritesTitle, favoriteChannels)
        }

        val byId = visible.associateBy { it.id }
        val recentChannels = recentIds.mapNotNull { byId[it] }.distinctBy { it.id }
        // An empty title means "no Recent rail on this screen"
        // (the mobile home passes "" and keeps its Continue card
        // instead) — never emit a blank-header rail.
        if (recentChannels.isNotEmpty() && recentsTitle.isNotEmpty()) {
            rails += Rail(recentsTitle, recentChannels)
        }

        for (group in GroupOverrides.effectiveGroups(visible, overrides)) {
            rails += Rail(group.displayName, group.channels)
        }
        return rails
    }
}
