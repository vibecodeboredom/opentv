package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 7.3.0: channel-logo cache refresh staleness. */
class LogoRefreshTest {

    private val now = 1_800_000_000_000L

    @Test
    fun neverRefreshedIsStale() {
        assertTrue(LogoRefresh.isStale(null, now))
    }

    @Test
    fun freshLogoIsNotStale() {
        assertFalse(LogoRefresh.isStale(now, now))
        assertFalse(LogoRefresh.isStale(now - LogoRefresh.LOGO_TTL_MILLIS, now))
    }

    @Test
    fun olderThanTtlIsStale() {
        assertTrue(LogoRefresh.isStale(now - LogoRefresh.LOGO_TTL_MILLIS - 1, now))
        assertTrue(LogoRefresh.isStale(now - 30L * 24 * 60 * 60 * 1000, now))
    }

    @Test
    fun futureTimestampIsNotStale() {
        assertFalse(LogoRefresh.isStale(now + 60_000L, now))
    }

    @Test
    fun ttlIsSevenDays() {
        assertEquals(7L * 24 * 60 * 60 * 1000, LogoRefresh.LOGO_TTL_MILLIS)
    }

    @Test
    fun staleUrlsFiltersAndDedupes() {
        val urls = listOf("a", "b", "a", "c")
        val lastRefresh = mapOf(
            "a" to now,
            "b" to (now - LogoRefresh.LOGO_TTL_MILLIS - 1)
        )
        assertEquals(listOf("b", "c"), LogoRefresh.staleUrls(urls, lastRefresh, now))
    }

    @Test
    fun staleUrlsEmptyWhenAllFresh() {
        val urls = listOf("a", "b")
        val lastRefresh = mapOf("a" to now, "b" to now)
        assertEquals(emptyList<String>(), LogoRefresh.staleUrls(urls, lastRefresh, now))
    }

    @Test
    fun staleUrlsHandlesEmptyInput() {
        assertEquals(emptyList<String>(), LogoRefresh.staleUrls(emptyList(), emptyMap(), now))
    }

    @Test
    fun staleUrlsTreatsMissingMapEntriesAsStale() {
        val urls = listOf("url1", "url2")
        val lastRefresh = mapOf("url1" to now)
        assertEquals(listOf("url2"), LogoRefresh.staleUrls(urls, lastRefresh, now))
    }
}
