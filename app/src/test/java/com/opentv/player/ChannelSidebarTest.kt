package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 8.1.1: the sidebar History count matches the History list. */
class ChannelSidebarTest {

    private fun channel(id: String, group: String) =
        Channel(
            id = id, name = id, url = "http://example.com/$id", group = group,
            logo = null, tvgId = null
        )

    private fun sampleChannels() = listOf(
        channel("c1", "News"),
        channel("c2", "News"),
        channel("c3", "Sports")
    )

    @Test
    fun allRecentsPresentCountsAll() {
        assertEquals(
            3,
            ChannelSidebar.historyCount(sampleChannels(), listOf("c1", "c2", "c3"), emptySet())
        )
    }

    @Test
    fun hiddenRecentIsNotCounted() {
        assertEquals(
            2,
            ChannelSidebar.historyCount(
                sampleChannels(), listOf("c1", "c2", "c3"), setOf("c2")
            )
        )
    }

    @Test
    fun recentGoneFromThePlaylistIsNotCounted() {
        assertEquals(
            2,
            ChannelSidebar.historyCount(
                sampleChannels(), listOf("c1", "c2", "gone"), emptySet()
            )
        )
    }

    @Test
    fun allRecentsHiddenOrGoneCountsZero() {
        assertEquals(
            0,
            ChannelSidebar.historyCount(
                sampleChannels(), listOf("c2", "gone"), setOf("c2")
            )
        )
    }

    @Test
    fun emptyRecentsCountsZero() {
        assertEquals(
            0,
            ChannelSidebar.historyCount(sampleChannels(), emptyList(), emptySet())
        )
    }

    /** 8.1.1: a duplicate id in recents counts once per occurrence,
     * matching the History list, which shows one row per recent id. */
    @Test
    fun duplicateRecentsCountPerOccurrence() {
        assertEquals(
            3,
            ChannelSidebar.historyCount(
                sampleChannels(), listOf("c1", "c1", "c2"), emptySet()
            )
        )
    }

    @Test
    fun channelIdsMatchCaseSensitively() {
        assertEquals(
            1,
            ChannelSidebar.historyCount(
                listOf(channel("Channel-A", "News")),
                listOf("channel-a", "CHANNEL-A", "Channel-A"), emptySet()
            )
        )
    }
}
