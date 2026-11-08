package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgParserTest {

    private val sample = """
        <tv>
          <programme channel="CNN.us" start="20261107200000 +0000" stop="20261107210000 +0000">
            <title>Evening News</title>
          </programme>
          <programme channel="CNN.us" start="20261107210000 +0000" stop="20261107220000 +0000">
            <title>Late Show</title>
          </programme>
          <programme channel="ESPN.us" start="20261107200000 +0000" stop="20261107223000 +0000">
            <title>Big Game</title>
          </programme>
        </tv>
    """.trimIndent()

    @Test
    fun parsesProgrammesByChannel() {
        val epg = EpgParser.parse(sample)
        assertEquals(2, epg["CNN.us"]?.size)
        assertEquals(1, epg["ESPN.us"]?.size)
        assertEquals("Evening News", epg["CNN.us"]?.get(0)?.title)
    }

    @Test
    fun nowAndNextFindsCurrentAndFollowing() {
        val epg = EpgParser.parse(sample)
        // 20:30 UTC on Nov 7 2026: Evening News is on, Late Show is next.
        val nowMillis = 1_794_083_400_000L // 2026-11-07 20:30:00 UTC
        val (now, next) = EpgParser.nowAndNext(epg["CNN.us"], nowMillis)
        assertEquals("Evening News", now?.title)
        assertEquals("Late Show", next?.title)
    }

    @Test
    fun nowAndNextIsNullForUnknownChannel() {
        val epg = EpgParser.parse(sample)
        val (now, next) = EpgParser.nowAndNext(epg["NOPE.us"])
        assertNull(now)
        assertNull(next)
    }

    @Test
    fun badXmlGivesEmptyMap() {
        assertTrue(EpgParser.parse("not xml at all").isEmpty())
        assertTrue(EpgParser.parse("").isEmpty())
    }
}
