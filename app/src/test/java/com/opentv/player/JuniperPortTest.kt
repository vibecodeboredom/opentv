package com.opentv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** Combined port (from juniper-v10 9.3.0): the pure parts of the
 * accessibility extras that landed in playback — the long-press
 * OK action parsing and the spoken channel-name utterance. */
class JuniperPortTest {

    @Test
    fun longPressOkActionParsesEveryKey() {
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction("menu")
        )
        assertEquals(
            AppSettings.LongPressOkAction.GUIDE,
            AppSettings.parseLongPressOkAction("guide")
        )
        assertEquals(
            AppSettings.LongPressOkAction.NOTHING,
            AppSettings.parseLongPressOkAction("nothing")
        )
    }

    @Test
    fun longPressOkActionDefaultsToMenu() {
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction(null)
        )
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction("bogus")
        )
        assertEquals(
            AppSettings.LongPressOkAction.MENU,
            AppSettings.parseLongPressOkAction("")
        )
    }

    @Test
    fun longPressOkActionKeysRoundTrip() {
        for (action in AppSettings.LongPressOkAction.values()) {
            assertEquals(
                action,
                AppSettings.parseLongPressOkAction(
                    AppSettings.longPressOkActionKey(action)
                )
            )
        }
    }

    @Test
    fun utteranceIsJustTheChannelName() {
        assertEquals("CNN", ChannelAnnouncer.utteranceFor("CNN"))
        assertEquals("", ChannelAnnouncer.utteranceFor(""))
    }
}
