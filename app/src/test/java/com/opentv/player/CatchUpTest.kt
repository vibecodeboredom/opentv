package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 9.1.1 (Nova): the archive-expiry helper behind the guide's
 * "Catch-up ends in Xh" line. Its whole point is agreeing with
 * [CatchUp.isAvailable] about where the window ends, so the
 * boundary is pinned from both sides. */
class CatchUpTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000

    private fun channel(catchupDays: Int?, catchupType: String? = "default") =
        Channel(
            id = "ch1", name = "One", url = "https://example.com/1.m3u8",
            group = "Test", logo = null, tvgId = "ch1",
            catchupType = catchupType, catchupDays = catchupDays
        )

    private fun programme(start: Long) =
        EpgParser.Programme("ch1", start, start + hour, "Show")

    @Test
    fun noCatchupMeansNoExpiry() {
        val plain = Channel(
            id = "ch1", name = "One", url = "https://example.com/1.m3u8",
            group = "Test", logo = null, tvgId = "ch1"
        )
        assertNull(CatchUp.archiveEndsMillis(plain, programme(1_000_000L)))
    }

    @Test
    fun expiryIsStartPlusArchiveDays() {
        val start = 1_794_081_600_000L
        assertEquals(
            start + 3 * day,
            CatchUp.archiveEndsMillis(channel(catchupDays = 3), programme(start))
        )
    }

    @Test
    fun missingDaysFallsBackToDefault() {
        val start = 1_794_081_600_000L
        assertEquals(
            start + CatchUp.DEFAULT_CATCHUP_DAYS * day,
            CatchUp.archiveEndsMillis(channel(catchupDays = null), programme(start))
        )
    }

    @Test
    fun availabilityEndsExactlyAtExpiry() {
        val start = 1_794_081_600_000L
        val prog = programme(start)
        val ch = channel(catchupDays = 3)
        val expiry = CatchUp.archiveEndsMillis(ch, prog)!!
        assertTrue(CatchUp.isAvailable(ch, prog, expiry))
        assertFalse(CatchUp.isAvailable(ch, prog, expiry + 1))
    }
}
