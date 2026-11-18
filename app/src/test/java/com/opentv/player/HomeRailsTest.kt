package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 9.0.0 — the shared home rails the TV home and the mobile home
 * both build from. Pure logic, no Context.
 */
class HomeRailsTest {

    private fun channel(id: String, name: String, group: String) =
        Channel(id = id, name = name, url = "http://x/$id", group = group, logo = null, tvgId = null)

    private val channels = listOf(
        channel("cnn", "CNN", "News"),
        channel("bbc", "BBC News", "News"),
        channel("espn", "ESPN", "Sports"),
        channel("hbo", "HBO", "Movies")
    )

    private fun rails(
        channels: List<Channel> = this.channels,
        favorites: Set<String> = emptySet(),
        recentIds: List<String> = emptyList(),
        hidden: Set<String> = emptySet(),
        overrides: GroupOverrides = GroupOverrides()
    ) = HomeRails.buildRails(channels, favorites, recentIds, hidden, overrides, "Favorites", "Recent channels")

    @Test
    fun `no favorites no recents is just the group rails in playlist order`() {
        val result = rails()
        assertEquals(listOf("News", "Sports", "Movies"), result.map { it.title })
        assertEquals(listOf("cnn", "bbc"), result[0].channels.map { it.id })
        assertEquals(listOf("espn"), result[1].channels.map { it.id })
        assertEquals(listOf("hbo"), result[2].channels.map { it.id })
    }

    @Test
    fun `favorites rail comes first and favorites also stay in their group rail`() {
        val result = rails(favorites = setOf("espn"))
        assertEquals(listOf("Favorites", "News", "Sports", "Movies"), result.map { it.title })
        assertEquals(listOf("espn"), result[0].channels.map { it.id })
        assertEquals(listOf("espn"), result[2].channels.map { it.id })
    }

    @Test
    fun `recents rail sits between favorites and groups, in recorded order`() {
        val result = rails(
            favorites = setOf("hbo"),
            recentIds = listOf("espn", "cnn", "gone")
        )
        assertEquals(
            listOf("Favorites", "Recent channels", "News", "Sports", "Movies"),
            result.map { it.title }
        )
        // "gone" is not in the playlist, so it drops out; order is
        // the recorded order, not playlist order.
        assertEquals(listOf("espn", "cnn"), result[1].channels.map { it.id })
    }

    @Test
    fun `a recent channel appears only once in the recents rail`() {
        val result = rails(recentIds = listOf("cnn", "cnn", "espn"))
        assertEquals(listOf("cnn", "espn"), result[0].channels.map { it.id })
    }

    @Test
    fun `hidden channels are excluded from every rail`() {
        val result = rails(
            favorites = setOf("cnn", "espn"),
            recentIds = listOf("cnn", "espn"),
            hidden = setOf("cnn")
        )
        // CNN gone everywhere; ESPN is the only favorite and recent.
        assertEquals(listOf("Favorites", "Recent channels", "News", "Sports", "Movies"), result.map { it.title })
        assertEquals(listOf("espn"), result[0].channels.map { it.id })
        assertEquals(listOf("espn"), result[1].channels.map { it.id })
        assertEquals(listOf("bbc"), result[2].channels.map { it.id })
    }

    @Test
    fun `hidden favorite leaves no favorites rail`() {
        val result = rails(favorites = setOf("cnn"), hidden = setOf("cnn"))
        assertTrue(result.none { it.title == "Favorites" })
    }

    @Test
    fun `group renames and moves apply to the group rails`() {
        val overrides = GroupOverrides(
            renames = mapOf("News" to "World News"),
            moves = mapOf("espn" to "News")
        )
        val result = rails(overrides = overrides)
        // Sports lost its only channel to the move, so it has no rail.
        assertEquals(listOf("World News", "Movies"), result.map { it.title })
        assertEquals(listOf("cnn", "bbc", "espn"), result[0].channels.map { it.id })
        assertEquals(listOf("hbo"), result[1].channels.map { it.id })
    }

    @Test
    fun `custom group order is honoured`() {
        val overrides = GroupOverrides(order = listOf("Movies", "News"))
        val result = rails(overrides = overrides)
        assertEquals(listOf("Movies", "News", "Sports"), result.map { it.title })
    }

    @Test
    fun `recents with an empty title build no recents rail`() {
        // The mobile home passes "" for the title (it keeps its
        // Continue card instead) — a blank-header rail must never
        // appear, even if recents were passed by mistake.
        val result = HomeRails.buildRails(
            channels, emptySet(), listOf("cnn", "espn"), emptySet(),
            GroupOverrides(), "Favorites", ""
        )
        assertEquals(listOf("News", "Sports", "Movies"), result.map { it.title })
        assertTrue(result.none { it.title.isEmpty() })
    }

    @Test
    fun `empty playlist builds no rails`() {
        assertTrue(rails(channels = emptyList()).isEmpty())
    }

    @Test
    fun `all channels hidden builds no rails`() {
        val result = rails(hidden = setOf("cnn", "bbc", "espn", "hbo"))
        assertTrue(result.isEmpty())
    }

    // ---- Gemini-generated (verified by hand, fixed where wrong) ----

    @Test
    fun `a favorite that is also a recent appears in all three rails`() {
        val one = listOf(channel("cnn", "CNN", "News"))
        val result = rails(
            channels = one, favorites = setOf("cnn"), recentIds = listOf("cnn")
        )
        assertEquals(3, result.size)
        assertEquals("Favorites", result[0].title)
        assertEquals(one, result[0].channels)
        assertEquals("Recent channels", result[1].title)
        assertEquals(one, result[1].channels)
        assertEquals("News", result[2].title)
        assertEquals(one, result[2].channels)
    }

    @Test
    fun `recents order is recorded order not playlist order even when reversed`() {
        val ch1 = channel("c1", "Ch 1", "News")
        val ch2 = channel("c2", "Ch 2", "News")
        val ch3 = channel("c3", "Ch 3", "News")
        val result = rails(
            channels = listOf(ch1, ch2, ch3), recentIds = listOf("c3", "c1", "c2")
        )
        val recentsRail = result.find { it.title == "Recent channels" }
        assertNotNull(recentsRail)
        assertEquals(listOf(ch3, ch1, ch2), recentsRail!!.channels)
    }

    @Test
    fun `a moved channel group rail uses its new group and the old group rail shrinks`() {
        val ch1 = channel("c1", "Ch 1", "Sports")
        val ch2 = channel("c2", "Ch 2", "News")
        val result = rails(
            channels = listOf(ch1, ch2),
            overrides = GroupOverrides(moves = mapOf("c1" to "News"))
        )
        // Sports lost its only channel, so it has no rail; the News
        // rail keeps playlist order (ch1 first), not move order.
        assertNull(result.find { it.title == "Sports" })
        val newsRail = result.find { it.title == "News" }
        assertNotNull(newsRail)
        assertEquals(listOf(ch1, ch2), newsRail!!.channels)
    }

    @Test
    fun `a renamed group shows the display name in the rail title`() {
        val one = listOf(channel("c1", "Ch 1", "Local_News"))
        val result = rails(
            channels = one,
            overrides = GroupOverrides(renames = mapOf("Local_News" to "Local & World News"))
        )
        assertEquals(1, result.size)
        assertEquals("Local & World News", result[0].title)
        assertEquals(one, result[0].channels)
    }

    @Test
    fun `hidden recents drop out of the recents rail but other recents stay`() {
        val ch1 = channel("c1", "Ch 1", "News")
        val ch2 = channel("c2", "Ch 2", "News")
        val result = rails(
            channels = listOf(ch1, ch2),
            recentIds = listOf("c1", "c2"),
            hidden = setOf("c1")
        )
        val recentsRail = result.find { it.title == "Recent channels" }
        assertNotNull(recentsRail)
        assertEquals(listOf(ch2), recentsRail!!.channels)
    }

    @Test
    fun `favorites rail keeps playlist order not favorite-set order`() {
        val ch1 = channel("c1", "Ch 1", "News")
        val ch2 = channel("c2", "Ch 2", "News")
        val ch3 = channel("c3", "Ch 3", "News")
        val result = rails(
            channels = listOf(ch1, ch2, ch3),
            favorites = setOf("c3", "c1", "c2")
        )
        val favoritesRail = result.find { it.title == "Favorites" }
        assertNotNull(favoritesRail)
        assertEquals(listOf(ch1, ch2, ch3), favoritesRail!!.channels)
    }

    @Test
    fun `a channel moved into a renamed group`() {
        val one = listOf(channel("c1", "Ch 1", "OldGroup"))
        val result = rails(
            channels = one,
            overrides = GroupOverrides(
                renames = mapOf("NewGroup" to "Awesome Group"),
                moves = mapOf("c1" to "NewGroup")
            )
        )
        assertEquals(1, result.size)
        assertEquals("Awesome Group", result[0].title)
        assertEquals(one, result[0].channels)
    }

    @Test
    fun `recents referencing a hidden channel id are dropped`() {
        val result = rails(
            channels = listOf(channel("c1", "Ch 1", "News")),
            recentIds = listOf("c1"),
            hidden = setOf("c1")
        )
        // The only recent is hidden, and it was the only channel,
        // so no rail at all is built.
        assertNull(result.find { it.title == "Recent channels" })
        assertTrue(result.isEmpty())
    }
}
