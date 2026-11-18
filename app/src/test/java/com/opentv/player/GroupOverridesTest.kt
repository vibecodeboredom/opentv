package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.1.0: custom channel groups — pure override logic. */
class GroupOverridesTest {

    private fun channel(id: String, name: String, group: String) =
        Channel(
            id = id, name = name, url = "http://example.com/$id", group = group,
            logo = null, tvgId = null
        )

    private fun sampleChannels() = listOf(
        channel("c1", "News One", "News"),
        channel("c2", "News Two", "News"),
        channel("c3", "Sports One", "Sports"),
        channel("c4", "Movie One", "Movies"),
        channel("c5", "Movie Two", "Movies")
    )

    @Test
    fun emptyOverridesKeepPlaylistGroupsInOrder() {
        val groups = GroupOverrides.effectiveGroups(
            sampleChannels(), GroupOverrides()
        )
        assertEquals(listOf("News", "Sports", "Movies"), groups.map { it.key })
        assertEquals(listOf("News", "Sports", "Movies"), groups.map { it.displayName })
        assertEquals(listOf("c1", "c2"), groups[0].channels.map { it.id })
        assertEquals(listOf("c3"), groups[1].channels.map { it.id })
    }

    @Test
    fun renameChangesOnlyTheDisplayName() {
        val overrides = GroupOverrides(renames = mapOf("News" to "My News"))
        val groups = GroupOverrides.effectiveGroups(sampleChannels(), overrides)
        assertEquals("News", groups[0].key)
        assertEquals("My News", groups[0].displayName)
        assertEquals(listOf("c1", "c2"), groups[0].channels.map { it.id })
    }

    @Test
    fun movePutsChannelInItsNewGroup() {
        val overrides = GroupOverrides(moves = mapOf("c2" to "Sports"))
        val groups = GroupOverrides.effectiveGroups(sampleChannels(), overrides)
        assertEquals(listOf("c1"), groups[0].channels.map { it.id })
        assertEquals(listOf("c2", "c3"), groups[1].channels.map { it.id })
        assertEquals("Sports", GroupOverrides.effectiveGroup(sampleChannels()[1], overrides))
    }

    @Test
    fun orderPutsListedGroupsFirstInThatOrder() {
        val overrides = GroupOverrides(order = listOf("Movies", "News"))
        val groups = GroupOverrides.effectiveGroups(sampleChannels(), overrides)
        assertEquals(listOf("Movies", "News", "Sports"), groups.map { it.key })
    }

    @Test
    fun orderIgnoresUnknownGroups() {
        val overrides = GroupOverrides(order = listOf("Nope", "Sports"))
        val groups = GroupOverrides.effectiveGroups(sampleChannels(), overrides)
        assertEquals(listOf("Sports", "News", "Movies"), groups.map { it.key })
    }

    @Test
    fun blankGroupLandsInUngrouped() {
        val channels = sampleChannels() + channel("c6", "Mystery", "")
        val groups = GroupOverrides.effectiveGroups(channels, GroupOverrides())
        assertEquals("Ungrouped", groups.last().key)
        assertEquals(listOf("c6"), groups.last().channels.map { it.id })
    }

    @Test
    fun reorderedOrderMovesOneStepUp() {
        val order = GroupOverrides.reorderedOrder(
            listOf("News", "Sports", "Movies"),
            listOf("News", "Sports", "Movies"), "Movies", up = true
        )
        assertEquals(listOf("News", "Movies", "Sports"), order)
    }

    @Test
    fun reorderedOrderMovesOneStepDown() {
        val order = GroupOverrides.reorderedOrder(
            listOf("News", "Sports", "Movies"),
            listOf("News", "Sports", "Movies"), "News", up = false
        )
        assertEquals(listOf("Sports", "News", "Movies"), order)
    }

    @Test
    fun reorderedOrderNoOpAtTheEnds() {
        assertEquals(
            listOf("News", "Sports", "Movies"),
            GroupOverrides.reorderedOrder(
                listOf("News", "Sports", "Movies"),
                listOf("News", "Sports", "Movies"), "News", up = true
            )
        )
        assertEquals(
            listOf("News", "Sports", "Movies"),
            GroupOverrides.reorderedOrder(
                listOf("News", "Sports", "Movies"),
                listOf("News", "Sports", "Movies"), "Movies", up = false
            )
        )
    }

    @Test
    fun reorderedOrderPullsInMissingGroups() {
        val order = GroupOverrides.reorderedOrder(
            listOf("Movies"),
            listOf("News", "Sports", "Movies"), "Sports", up = true
        )
        assertEquals(listOf("Movies", "Sports", "News"), order)
    }

    @Test
    fun mapRoundTripKeepsEntries() {
        val map = mapOf("News" to "My News", "c1" to "Sports")
        assertEquals(map, GroupOverrides.decodeMap(GroupOverrides.encodeMap(map)))
    }

    @Test
    fun mapDecodeSkipsBrokenLines() {
        val decoded = GroupOverrides.decodeMap(
            "News\u0001My News\nbroken\n\u0001no-key\nkey\u0001\nc1\u0001Sports"
        )
        assertEquals(mapOf("News" to "My News", "c1" to "Sports"), decoded)
    }

    @Test
    fun orderRoundTripKeepsOrderAndDropsBlanksAndDuplicates() {
        val decoded = GroupOverrides.decodeOrder(
            GroupOverrides.encodeOrder(listOf("Movies", "News"))
        )
        assertEquals(listOf("Movies", "News"), decoded)
        assertEquals(
            listOf("News", "Sports"),
            GroupOverrides.decodeOrder("News\n\nSports\nNews\n")
        )
    }

    @Test
    fun cleanGroupNameStripsNewlinesAndTabs() {
        assertEquals(
            "My News",
            GroupOverrides.cleanGroupName("My\nNews\t")
        )
        assertTrue(GroupOverrides.cleanGroupName("  \n ").isBlank())
    }

    @Test
    fun movedChannelInheritsRenamedGroup() {
        val ch = channel("c1", "News 24", "News")
        val overrides = GroupOverrides(
            moves = mapOf("c1" to "News"),
            renames = mapOf("News" to "Current Affairs")
        )
        val groups = GroupOverrides.effectiveGroups(listOf(ch), overrides)
        assertEquals(1, groups.size)
        assertEquals("News", groups[0].key)
        assertEquals("Current Affairs", groups[0].displayName)
        assertEquals(listOf(ch), groups[0].channels)
    }

    @Test
    fun decodeOrderKeepsFirstOccurrenceOfDuplicates() {
        assertEquals(
            listOf("Sports", "News", "Movies"),
            GroupOverrides.decodeOrder("Sports\nNews\nSports\nMovies\nNews")
        )
    }

    /** 8.1.1: sanitize keeps good entries and drops / cleans the bad ones. */
    @Test
    fun sanitizeDropsBlankEntriesAndCleansNames() {
        val cleaned = GroupOverrides.sanitize(
            GroupOverrides(
                renames = mapOf(
                    "News" to "My\nNews",
                    "Sports" to "  ",
                    "" to "No Key",
                    "Movies" to "Movies"
                ),
                order = listOf("Movies", "News", "Movies", "  ", "Sports"),
                moves = mapOf(
                    "c1" to "Sports",
                    "c2" to "  ",
                    "" to "News"
                )
            )
        )
        assertEquals(mapOf("News" to "My News", "Movies" to "Movies"), cleaned.renames)
        assertEquals(listOf("Movies", "News", "Sports"), cleaned.order)
        assertEquals(mapOf("c1" to "Sports"), cleaned.moves)
    }

    @Test
    fun sanitizeKeepsEntriesForGroupsThatNoLongerExist() {
        // Overrides are global across playlists — an entry for a
        // group that is not in the active playlist must survive a
        // sanitize, or switching playlists would destroy it.
        val cleaned = GroupOverrides.sanitize(
            GroupOverrides(
                renames = mapOf("Old Group" to "Renamed"),
                order = listOf("Old Group"),
                moves = mapOf("gone-channel" to "Old Group")
            )
        )
        assertEquals(mapOf("Old Group" to "Renamed"), cleaned.renames)
        assertEquals(listOf("Old Group"), cleaned.order)
        assertEquals(mapOf("gone-channel" to "Old Group"), cleaned.moves)
    }

    @Test
    fun sanitizeOfEmptyIsEmpty() {
        assertEquals(GroupOverrides(), GroupOverrides.sanitize(GroupOverrides()))
    }

    /** 8.1.1: sanitize cleans whitespace inside group names — but
     * a move's KEY is a channel id, not a group name, so it is left
     * alone (cleaning it would break the id lookup); only the
     * target group is cleaned. */
    @Test
    fun sanitizeCleansWhitespaceInGroupNamesOnly() {
        val cleaned = GroupOverrides.sanitize(
            GroupOverrides(
                renames = mapOf(
                    "Group\nOne" to "Group\rTwo",
                    "Group\u0001Three" to "Group\tFour"
                ),
                order = listOf("Group\tOne", "Group\u0001Two"),
                moves = mapOf("c1" to "Target\rTwo")
            )
        )
        assertEquals(
            mapOf("Group One" to "Group Two", "Group Three" to "Group Four"),
            cleaned.renames
        )
        assertEquals(listOf("Group One", "Group Two"), cleaned.order)
        assertEquals(mapOf("c1" to "Target Two"), cleaned.moves)
    }
}
