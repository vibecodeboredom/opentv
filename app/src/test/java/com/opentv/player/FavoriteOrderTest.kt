package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** 9.1.0 (Nova): the custom favorites order's pure core —
 * PlaylistRepository.sortByFavoriteOrder. The prefs-backed
 * wrapper (applyFavoriteOrder) is a one-line delegation to it. */
class FavoriteOrderTest {

    private fun channel(id: String) =
        Channel(
            id = id, name = id, url = "https://example.com/$id.m3u8",
            group = "Test", logo = null, tvgId = id
        )

    private fun ids(channels: List<Channel>) = channels.map { it.id }

    @Test
    fun emptyOrderKeepsPlaylistOrder() {
        val favorites = listOf(channel("a"), channel("b"), channel("c"))
        assertEquals(
            listOf("a", "b", "c"),
            ids(PlaylistRepository.sortByFavoriteOrder(emptyList(), favorites))
        )
    }

    @Test
    fun fullOrderReorders() {
        val favorites = listOf(channel("a"), channel("b"), channel("c"))
        assertEquals(
            listOf("c", "a", "b"),
            ids(
                PlaylistRepository.sortByFavoriteOrder(
                    listOf("c", "a", "b"), favorites
                )
            )
        )
    }

    @Test
    fun partialOrderRanksFirstAndAppendsTheRestInPlaylistOrder() {
        // "d" was favorited after the order was set: it lands at
        // the end; "b" and "a" keep their playlist order between
        // the ranked head and the unranked tail.
        val favorites = listOf(
            channel("a"), channel("b"), channel("c"), channel("d")
        )
        assertEquals(
            listOf("c", "a", "b", "d"),
            ids(
                PlaylistRepository.sortByFavoriteOrder(
                    listOf("c"), favorites
                )
            )
        )
    }

    @Test
    fun orderNamingNonFavoritesIsIgnored() {
        // The stored order can name a channel that was
        // un-favorited by another path: it simply never matches.
        val favorites = listOf(channel("a"), channel("b"))
        assertEquals(
            listOf("b", "a"),
            ids(
                PlaylistRepository.sortByFavoriteOrder(
                    listOf("gone", "b", "a"), favorites
                )
            )
        )
    }

    @Test
    fun duplicateIdsInOrderDoNotDuplicateChannels() {
        val favorites = listOf(channel("a"), channel("b"))
        assertEquals(
            listOf("b", "a"),
            ids(
                PlaylistRepository.sortByFavoriteOrder(
                    listOf("b", "b", "a"), favorites
                )
            )
        )
    }
}
