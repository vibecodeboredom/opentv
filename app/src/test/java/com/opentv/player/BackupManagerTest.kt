package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 8.0.0: backup encode / decode round-trip and rejection of bad files. */
class BackupManagerTest {

    private fun sampleData() = BackupManager.BackupData(
        playlists = listOf(
            BackupManager.BackupPlaylist("US Playlist", "https://example.com/us.m3u", true),
            BackupManager.BackupPlaylist(
                "Shared", "file:shared_1.m3u", false,
                "#EXTM3U\n#EXTINF:-1,Ch 1\nhttp://example.com/1"
            )
        ),
        activePlaylistUrl = "https://example.com/us.m3u",
        epgUrls = listOf("https://i.mjh.nz/PlutoTV/us.xml", "https://example.com/guide.xml"),
        uploadUrl = "https://user:pass@example.com/recordings",
        favorites = setOf("ch-1", "ch-2"),
        hiddenChannels = setOf("ch-9"),
        epgOverrides = mapOf("ch-1" to "pluto.us"),
        trackPrefs = mapOf("a:ch-1" to "eng", "s:ch-1" to "off"),
        guideOpensOnCurrentChannel = false,
        tvInfoFade = true,
        guideShowsPast = false,
        guideFavoritesOnly = true,
        channelListSize = "large",
        guideWindowSize = "12"
    )

    @Test
    fun roundTripKeepsEverything() {
        val data = sampleData()
        val decoded = BackupManager.decode(BackupManager.encode(data)).getOrThrow()
        assertEquals(data, decoded)
    }

    @Test
    fun groupOverridesSurviveTheRoundTrip() {
        val data = sampleData().copy(
            groupOverrides = GroupOverrides(
                renames = mapOf("News" to "My News"),
                order = listOf("Movies", "News"),
                moves = mapOf("ch-1" to "Sports")
            )
        )
        val decoded = BackupManager.decode(BackupManager.encode(data)).getOrThrow()
        assertEquals(data.groupOverrides, decoded.groupOverrides)
    }

    @Test
    fun oldBackupWithoutGroupOverridesDecodesToEmpty() {
        val text = """{"format":"opentv-backup","version":1,"playlists":[],"epgUrls":[],"uploadUrl":"","favorites":[],"hiddenChannels":[],"epgOverrides":{},"trackPrefs":{},"settings":{}}"""
        val decoded = BackupManager.decode(text).getOrThrow()
        assertEquals(GroupOverrides(), decoded.groupOverrides)
        // 8.1.1: the missing key is remembered, so a restore of
        // this old backup does not wipe the overrides on the device.
        assertFalse(decoded.hasGroupOverrides)
    }

    @Test
    fun roundTripBackupCarriesGroupOverrides() {
        val decoded = BackupManager.decode(BackupManager.encode(sampleData())).getOrThrow()
        assertTrue(decoded.hasGroupOverrides)
    }

    /** 8.1.1: a backup with an (empty) groupOverrides object still
     * counts as carrying overrides — only a missing key means
     * "this backup predates them". */
    @Test
    fun emptyGroupOverridesObjectCountsAsPresent() {
        val text = """{"format":"opentv-backup","version":1,"playlists":[],"groupOverrides":{},"settings":{}}"""
        val decoded = BackupManager.decode(text).getOrThrow()
        assertTrue(decoded.hasGroupOverrides)
        assertEquals(GroupOverrides(), decoded.groupOverrides)
    }

    @Test
    fun encodedTextIsMarkedAsABackup() {
        val text = BackupManager.encode(sampleData())
        assertTrue(text.contains("\"format\":\"opentv-backup\""))
        assertTrue(text.contains("\"version\":1"))
    }

    @Test
    fun localPlaylistTextSurvivesTheRoundTrip() {
        val decoded = BackupManager.decode(BackupManager.encode(sampleData())).getOrThrow()
        assertEquals(
            "#EXTM3U\n#EXTINF:-1,Ch 1\nhttp://example.com/1",
            decoded.playlists[1].localText
        )
        assertFalse(decoded.playlists[1].enabled)
    }

    @Test
    fun specialCharactersSurviveTheRoundTrip() {
        val data = sampleData().copy(
            playlists = listOf(
                BackupManager.BackupPlaylist(
                    "Weird \"Name\" \\ with \n newline", "https://example.com/a?x=1&y=2", true
                )
            ),
            favorites = setOf("ch-\"quoted\"")
        )
        val decoded = BackupManager.decode(BackupManager.encode(data)).getOrThrow()
        assertEquals(data.playlists[0].name, decoded.playlists[0].name)
        assertEquals(setOf("ch-\"quoted\""), decoded.favorites)
    }

    @Test
    fun garbageTextIsRejected() {
        assertTrue(BackupManager.decode("not json at all").isFailure)
        assertTrue(BackupManager.decode("").isFailure)
        assertTrue(BackupManager.decode("{}").isFailure)
        assertTrue(BackupManager.decode("[]").isFailure)
    }

    @Test
    fun wrongFormatIsRejected() {
        val text = BackupManager.encode(sampleData())
            .replace("opentv-backup", "something-else")
        assertTrue(BackupManager.decode(text).isFailure)
    }

    @Test
    fun futureVersionIsRejected() {
        val text = BackupManager.encode(sampleData()).replace("\"version\":1", "\"version\":99")
        assertTrue(BackupManager.decode(text).isFailure)
    }

    @Test
    fun missingFieldsFallBackToDefaults() {
        val decoded = BackupManager.decode(
            """{"format":"opentv-backup","version":1,"playlists":[]}"""
        ).getOrThrow()
        assertTrue(decoded.epgUrls.isEmpty())
        assertTrue(decoded.favorites.isEmpty())
        assertTrue(decoded.guideOpensOnCurrentChannel)
        assertTrue(decoded.tvInfoFade)
        assertTrue(decoded.guideShowsPast)
        assertFalse(decoded.guideFavoritesOnly)
        assertEquals("small", decoded.channelListSize)
        assertEquals("6", decoded.guideWindowSize)
    }

    @Test
    fun malformedPlaylistEntriesAreSkippedNotFatal() {
        val decoded = BackupManager.decode(
            """{"format":"opentv-backup","version":1,"playlists":[
              {"name":"Good","url":"https://example.com/a.m3u"},
              {"name":"No url"},
              "not an object",
              {"name":"Also good","url":"https://example.com/b.m3u","enabled":false}
            ]}"""
        ).getOrThrow()
        assertEquals(2, decoded.playlists.size)
        assertEquals("Good", decoded.playlists[0].name)
        assertTrue(decoded.playlists[0].enabled)
        assertFalse(decoded.playlists[1].enabled)
    }

    @Test
    fun decodeHandlesUnicodeEscapesInJsonStrings() {
        val decoded = BackupManager.decode(
            """{"format":"opentv-backup","version":1,"playlists":[
              {"name":"Caf\u00e9 \u2603","url":"https://example.com/a.m3u"}
            ]}"""
        ).getOrThrow()
        assertEquals("Café ☃", decoded.playlists[0].name)
    }

    @Test
    fun decodeRejectsVersionZero() {
        val result = BackupManager.decode(
            """{"format":"opentv-backup","version":0,"playlists":[]}"""
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun decodeHandlesEmptyBackupWithNoPlaylists() {
        val decoded = BackupManager.decode(
            """{"format":"opentv-backup","version":1,"playlists":[]}"""
        ).getOrThrow()
        assertTrue(decoded.playlists.isEmpty())
    }
}

/** 8.0.0: EPG source list parsing (the storage split). */
class EpgUrlsTest {

    @Test
    fun commaSeparatedLegacyValueSplits() {
        assertEquals(
            listOf("https://a.example.com/x.xml", "https://b.example.com/y.xml"),
            PlaylistRepository.parseEpgUrls(
                "https://a.example.com/x.xml, https://b.example.com/y.xml"
            )
        )
    }

    @Test
    fun newlineJoinedValueSplits() {
        assertEquals(
            listOf("https://a.example.com/x.xml", "https://b.example.com/y.xml"),
            PlaylistRepository.parseEpgUrls(
                "https://a.example.com/x.xml\nhttps://b.example.com/y.xml"
            )
        )
    }

    @Test
    fun blankValueFallsBackToDefault() {
        assertEquals(
            listOf(PlaylistRepository.DEFAULT_EPG_URL),
            PlaylistRepository.parseEpgUrls("")
        )
        assertEquals(
            listOf(PlaylistRepository.DEFAULT_EPG_URL),
            PlaylistRepository.parseEpgUrls("   \n , ")
        )
    }

    @Test
    fun singleUrlStaysSingle() {
        assertEquals(
            listOf("https://i.mjh.nz/PlutoTV/us.xml"),
            PlaylistRepository.parseEpgUrls("https://i.mjh.nz/PlutoTV/us.xml")
        )
    }
}
