package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 10.0.0 (Nova): the backup codec's pure core — encode a
 * BackupData, parse it back, and check the strict validation
 * that keeps a malformed file from touching the stores. The
 * Context-backed collect/apply are thin glue over the stores
 * and are not exercised here. */
class NovaBackupTest {

    private fun fullBackup() = NovaBackup.BackupData(
        playlists = listOf(
            NovaBackup.PlaylistEntry("US Playlist", "https://example.com/us.m3u", true),
            NovaBackup.PlaylistEntry("Old One", "https://example.com/old.m3u", false)
        ),
        activePlaylistUrl = "https://example.com/us.m3u",
        epgUrl = "https://example.com/epg.xml",
        uploadUrl = "",
        favorites = listOf("chan-b", "chan-a"),
        favoriteOrder = listOf("chan-a", "chan-b"),
        hiddenChannels = listOf("chan-x"),
        reminders = listOf(
            NovaBackup.ReminderEntry(
                "chan-a", "Channel A", 1_700_000_000_000L, 1_700_003_600_000L,
                "The Big Match"
            )
        ),
        scheduledRecordings = listOf(
            NovaBackup.ScheduledEntry(
                "chan-b", "Channel B", "https://example.com/b.m3u8",
                1_700_100_000_000L, 1_700_103_600_000L, "Late Film"
            )
        ),
        epgOverrides = mapOf("chan-a" to "a.example.tv"),
        keywords = listOf("news", "★ derby"),
        settings = NovaBackup.SettingsData(
            accentChoice = 2,
            guideTextSize = 1,
            channelListSize = "large",
            favoritesZap = true,
            nightDimPercent = 50,
            qualityMode = 2,
            guideZoomHours = 4,
            idleSaverEnabled = false,
            surfIntervalSeconds = 20,
            guideOpensOnCurrentChannel = false,
            tvInfoFade = true,
            guideShowsPast = false,
            recordingAutoDeleteCapBytes = 5L * 1024 * 1024 * 1024
        )
    )

    @Test
    fun roundTripPreservesEverything() {
        val data = fullBackup()
        assertEquals(data, NovaBackup.parseBackup(NovaBackup.encode(data)))
    }

    @Test
    fun emptyBackupRoundTrips() {
        val data = NovaBackup.BackupData()
        val parsed = NovaBackup.parseBackup(NovaBackup.encode(data))
        assertEquals(data, parsed)
    }

    @Test
    fun escapesAndUnicodeSurviveTheRoundTrip() {
        val tricky = "Quotes \"and\" \\ backslash\nnewline\tTab ★ émoji 😀"
        val data = NovaBackup.BackupData(
            playlists = listOf(NovaBackup.PlaylistEntry(tricky, "https://e.com/x.m3u", true)),
            keywords = listOf(tricky)
        )
        val parsed = NovaBackup.parseBackup(NovaBackup.encode(data))
        assertEquals(data, parsed)
    }

    @Test
    fun absentSectionsStayAbsent() {
        val parsed = NovaBackup.parseBackup(
            """{"format":"opentv-nova-backup","version":1,"favorites":["a"]}"""
        )
        assertEquals(listOf("a"), parsed?.favorites)
        assertNull(parsed?.playlists)
        assertNull(parsed?.settings)
        assertNull(parsed?.reminders)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(NovaBackup.parseBackup(""))
        assertNull(NovaBackup.parseBackup("not json"))
        assertNull(NovaBackup.parseBackup("{}"))
        assertNull(NovaBackup.parseBackup("""{"format":"other","version":1}"""))
        assertNull(NovaBackup.parseBackup("""{"format":"opentv-nova-backup"}"""))
    }

    @Test
    fun newerFormatVersionIsRejected() {
        assertNull(
            NovaBackup.parseBackup(
                """{"format":"opentv-nova-backup","version":99}"""
            )
        )
    }

    @Test
    fun trailingContentIsRejected() {
        val good = NovaBackup.encode(fullBackup())
        assertNull(NovaBackup.parseBackup(good + "extra"))
        assertNull(NovaBackup.parseBackup(good.dropLast(3)))
    }

    @Test
    fun wrongShapesAreRejected() {
        val head = """{"format":"opentv-nova-backup","version":1,"""
        // favorites must be a list of strings
        assertNull(NovaBackup.parseBackup(head + """"favorites":"a"}"""))
        assertNull(NovaBackup.parseBackup(head + """"favorites":[1]}"""))
        // a reminder missing a field
        assertNull(
            NovaBackup.parseBackup(
                head + """"reminders":[{"channelId":"a","channelName":"A",""" +
                    """"startMillis":1,"stopMillis":2}]}"""
            )
        )
        // a settings field with the wrong type
        assertNull(
            NovaBackup.parseBackup(head + """"settings":{"accentChoice":"violet"}}""")
        )
        // an empty playlist list would wipe the user's playlists
        assertNull(NovaBackup.parseBackup(head + """"playlists":[]}"""))
        // a playlist with a blank URL
        assertNull(
            NovaBackup.parseBackup(
                head + """"playlists":[{"name":"X","url":"","enabled":true}]}"""
            )
        )
    }

    @Test
    fun playlistEnabledDefaultsToTrueWhenMissing() {
        val parsed = NovaBackup.parseBackup(
            """{"format":"opentv-nova-backup","version":1,""" +
                """"playlists":[{"name":"X","url":"https://e.com/x.m3u"}]}"""
        )
        assertEquals(true, parsed?.playlists?.single()?.enabled)
    }

    @Test
    fun unknownSettingsKeysAreIgnored() {
        val parsed = NovaBackup.parseBackup(
            """{"format":"opentv-nova-backup","version":1,""" +
                """"settings":{"accentChoice":1,"futureThing":true}}"""
        )
        assertEquals(1, parsed?.settings?.accentChoice)
        assertTrue(parsed?.settings?.favoritesZap == null)
    }
}
