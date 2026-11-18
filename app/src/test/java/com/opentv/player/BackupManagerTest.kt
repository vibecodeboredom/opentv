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

    /** 9.1.0: the appearance settings ride in the backup, and an
     * old backup without them decodes to the defaults. 9.3.0: the
     * larger-text mode, spoken names and held-OK action ride too. */
    @Test
    fun appearanceSettingsSurviveTheRoundTrip() {
        val data = sampleData().copy(
            guideDensity = "compact",
            fontSize = "large",
            highContrastFocus = true,
            reducedAnimations = true,
            largerText = true,
            speakChannelNames = true,
            longPressOkAction = "guide"
        )
        val decoded = BackupManager.decode(BackupManager.encode(data)).getOrThrow()
        assertEquals("compact", decoded.guideDensity)
        assertEquals("large", decoded.fontSize)
        assertTrue(decoded.highContrastFocus)
        assertTrue(decoded.reducedAnimations)
        assertTrue(decoded.largerText)
        assertTrue(decoded.speakChannelNames)
        assertEquals("guide", decoded.longPressOkAction)
        // 9.3.1: a current backup carries the accessibility keys.
        assertTrue(decoded.hasAccessibilitySettings)
    }

    @Test
    fun oldBackupWithoutAppearanceDecodesToDefaults() {
        val text = """{"format":"opentv-backup","version":1,"playlists":[],"epgUrls":[],"uploadUrl":"","favorites":[],"hiddenChannels":[],"epgOverrides":{},"trackPrefs":{},"settings":{}}"""
        val decoded = BackupManager.decode(text).getOrThrow()
        assertEquals("comfortable", decoded.guideDensity)
        assertEquals("medium", decoded.fontSize)
        assertFalse(decoded.highContrastFocus)
        assertFalse(decoded.reducedAnimations)
        assertFalse(decoded.largerText)
        assertFalse(decoded.speakChannelNames)
        assertEquals("menu", decoded.longPressOkAction)
        // 9.3.1: an old backup has no accessibility keys, so a
        // restore must leave the user's current choices alone.
        assertFalse(decoded.hasAccessibilitySettings)
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

    // ---- Extra tests from Gemini lite (verified by hand, fixed to
    // unwrap the Result) ------------------------------------------

    @Test
    fun backupWithoutSettingsObjectFlagsAccessibilityAbsent() {
        val text = """{"format":"opentv-backup","version":1,"playlists":[],"epgUrls":[],"uploadUrl":"","favorites":[],"hiddenChannels":[],"epgOverrides":{},"trackPrefs":{}}"""
        val decoded = BackupManager.decode(text).getOrThrow()
        assertFalse(decoded.hasAccessibilitySettings)
    }

    @Test
    fun backupWithSettingsButNoLongPressKeyFlagsAccessibilityAbsent() {
        val text = """{"format":"opentv-backup","version":1,"playlists":[],"epgUrls":[],"uploadUrl":"","favorites":[],"hiddenChannels":[],"epgOverrides":{},"trackPrefs":{},"settings":{"someOtherSetting":true}}"""
        val decoded = BackupManager.decode(text).getOrThrow()
        assertFalse(decoded.hasAccessibilitySettings)
    }

    @Test
    fun backupWithLongPressKeyFlagsAccessibilityPresent() {
        // Presence is what matters — even an unknown key value
        // marks the batch as carried; parsing happens at restore.
        val text = """{"format":"opentv-backup","version":1,"playlists":[],"epgUrls":[],"uploadUrl":"","favorites":[],"hiddenChannels":[],"epgOverrides":{},"trackPrefs":{},"settings":{"longPressOkAction":"info"}}"""
        val decoded = BackupManager.decode(text).getOrThrow()
        assertTrue(decoded.hasAccessibilitySettings)
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

    // ---- Combine: Nova's old format ("opentv-nova-backup") --------
    //
    // NovaBackup is gone — the unified BackupManager decodes its
    // format too, so a backup written by the Nova line still
    // imports with nothing dropped.

    private val novaFormatJson = """
        {"format":"opentv-nova-backup","version":1,
        "playlists":[
          {"name":"US Playlist","url":"https://example.com/us.m3u","enabled":true},
          {"name":"Old One","url":"https://example.com/old.m3u","enabled":false}],
        "activePlaylistUrl":"https://example.com/us.m3u",
        "epgUrl":"https://example.com/epg.xml","uploadUrl":"",
        "favorites":["chan-b","chan-a"],
        "favoriteOrder":["chan-a","chan-b"],
        "hiddenChannels":["chan-x"],
        "reminders":[{"channelId":"chan-a","channelName":"Channel A",
          "startMillis":1700000000000,"stopMillis":1700003600000,
          "title":"The Big Match"}],
        "scheduledRecordings":[{"channelId":"chan-b","channelName":"Channel B",
          "channelUrl":"https://example.com/b.m3u8",
          "startMillis":1700100000000,"stopMillis":1700103600000,
          "title":"Late Film"}],
        "epgOverrides":{"chan-a":"a.example.tv"},
        "keywords":["news","★ derby"],
        "settings":{"accentChoice":2,"guideTextSize":1,"channelListSize":"large",
          "favoritesZap":true,"nightDimPercent":50,"qualityMode":2,
          "guideZoomHours":4,"idleSaverEnabled":false,"surfIntervalSeconds":20,
          "guideOpensOnCurrentChannel":false,"tvInfoFade":true,
          "guideShowsPast":false,
          "recordingAutoDeleteCapBytes":5368709120}}
    """.trimIndent()

    @Test
    fun novaFormatDecodesWithAllNovaFields() {
        val decoded = BackupManager.decode(novaFormatJson).getOrThrow()
        assertEquals(
            listOf(
                BackupManager.BackupPlaylist(
                    "US Playlist", "https://example.com/us.m3u", true
                ),
                BackupManager.BackupPlaylist(
                    "Old One", "https://example.com/old.m3u", false
                )
            ),
            decoded.playlists
        )
        assertEquals("https://example.com/us.m3u", decoded.activePlaylistUrl)
        assertEquals(listOf("https://example.com/epg.xml"), decoded.epgUrls)
        assertEquals(setOf("chan-b", "chan-a"), decoded.favorites)
        assertEquals(listOf("chan-a", "chan-b"), decoded.favoriteOrder)
        assertEquals(setOf("chan-x"), decoded.hiddenChannels)
        assertEquals(
            listOf(
                BackupManager.BackupReminder(
                    "chan-a", "Channel A",
                    1_700_000_000_000L, 1_700_003_600_000L, "The Big Match"
                )
            ),
            decoded.reminders
        )
        assertEquals(
            listOf(
                BackupManager.BackupScheduled(
                    "chan-b", "Channel B", "https://example.com/b.m3u8",
                    1_700_100_000_000L, 1_700_103_600_000L, "Late Film"
                )
            ),
            decoded.scheduledRecordings
        )
        assertEquals(mapOf("chan-a" to "a.example.tv"), decoded.epgOverrides)
        assertEquals(listOf("news", "★ derby"), decoded.keywords)
        assertEquals(2, decoded.accentChoice)
        assertEquals(1, decoded.guideTextSize)
        assertTrue(decoded.favoritesZap)
        assertEquals(50, decoded.nightDimPercent)
        assertEquals(2, decoded.qualityMode)
        assertEquals(4, decoded.guideZoomHours)
        assertFalse(decoded.idleSaverEnabled)
        assertEquals(20, decoded.surfIntervalSeconds)
        assertEquals(5L * 1024 * 1024 * 1024, decoded.autoDeleteCapBytes)
        assertEquals("large", decoded.channelListSize)
        assertFalse(decoded.guideOpensOnCurrentChannel)
        assertTrue(decoded.tvInfoFade)
        assertFalse(decoded.guideShowsPast)
        assertTrue(decoded.hasNovaData)
        // A Nova backup never carried Juniper's group overrides or
        // accessibility settings — restore must leave those alone.
        assertFalse(decoded.hasGroupOverrides)
        assertFalse(decoded.hasAccessibilitySettings)
    }

    @Test
    fun novaFormatMinimalFileFlagsNovaData() {
        val decoded = BackupManager.decode(
            """{"format":"opentv-nova-backup","version":1,"favorites":["a"]}"""
        ).getOrThrow()
        assertEquals(listOf("a"), decoded.favorites)
        assertTrue(decoded.hasNovaData)
        assertTrue(decoded.reminders.isEmpty())
        assertTrue(decoded.keywords.isEmpty())
    }

    @Test
    fun novaFormatFutureVersionIsRejected() {
        assertTrue(
            BackupManager.decode(
                """{"format":"opentv-nova-backup","version":99}"""
            ).isFailure
        )
    }

    @Test
    fun unifiedFormatWithNovaFieldsRoundTrips() {
        val data = sampleData().copy(
            favoriteOrder = listOf("ch-2", "ch-1"),
            reminders = listOf(
                BackupManager.BackupReminder(
                    "ch-1", "Channel 1", 1_700_000_000_000L,
                    1_700_003_600_000L, "Show"
                )
            ),
            scheduledRecordings = listOf(
                BackupManager.BackupScheduled(
                    "ch-2", "Channel 2", "https://example.com/2.m3u8",
                    1_700_100_000_000L, 1_700_103_600_000L, "Film"
                )
            ),
            keywords = listOf("news"),
            accentChoice = 1,
            guideTextSize = 1,
            favoritesZap = true,
            nightDimPercent = 25,
            qualityMode = 1,
            guideZoomHours = 4,
            idleSaverEnabled = false,
            surfIntervalSeconds = 30,
            autoDeleteCapBytes = 2L * 1024 * 1024 * 1024
        )
        val decoded = BackupManager.decode(BackupManager.encode(data)).getOrThrow()
        assertEquals(data, decoded)
    }

    @Test
    fun preCombineBackupWithoutNovaKeysFlagsNovaDataAbsent() {
        val decoded = BackupManager.decode(
            """{"format":"opentv-backup","version":1,"playlists":[]}"""
        ).getOrThrow()
        assertFalse(decoded.hasNovaData)
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
