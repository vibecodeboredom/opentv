package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {

    private val sample = """
        #EXTM3U
        #EXTINF:-1 tvg-id="CNN.us" tvg-logo="https://example.com/cnn.png" group-title="News",CNN
        https://example.com/cnn.m3u8
        #EXTINF:-1 tvg-id="ESPN.us" tvg-logo="https://example.com/espn.png" group-title="Sports",ESPN
        https://example.com/espn.m3u8
        #EXTINF:-1 group-title="Movies",Classic Movies
        https://example.com/movies.m3u8
    """.trimIndent()

    @Test
    fun parsesChannels() {
        val channels = M3uParser.parse(sample)
        assertEquals(3, channels.size)
    }

    @Test
    fun parsesAttributes() {
        val cnn = M3uParser.parse(sample)[0]
        assertEquals("CNN", cnn.name)
        assertEquals("https://example.com/cnn.m3u8", cnn.url)
        assertEquals("News", cnn.group)
        assertEquals("https://example.com/cnn.png", cnn.logo)
        assertEquals("CNN.us", cnn.tvgId)
    }

    @Test
    fun defaultsGroupWhenMissing() {
        val parsed = M3uParser.parse("#EXTINF:-1,No Group\nhttps://example.com/x.m3u8")
        assertEquals("Other", parsed[0].group)
        assertNull(parsed[0].logo)
    }

    @Test
    fun ignoresMalformedEntries() {
        val parsed = M3uParser.parse("#EXTINF:-1,Broken\n#EXTINF:-1,Good\nhttps://example.com/good.m3u8")
        assertEquals(1, parsed.size)
        assertEquals("Good", parsed[0].name)
    }

    @Test
    fun emptyInputGivesEmptyList() {
        assertTrue(M3uParser.parse("").isEmpty())
        assertTrue(M3uParser.parse("#EXTM3U").isEmpty())
    }
}
