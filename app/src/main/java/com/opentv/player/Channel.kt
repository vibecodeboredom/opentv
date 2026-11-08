package com.opentv.player

import java.io.Serializable

/**
 * One TV channel from an M3U playlist.
 */
data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val group: String,
    val logo: String?,
    val tvgId: String?,
    /** Position in the playlist (1-based), shown as the channel number. */
    val number: Int = 0
) : Serializable

/** A small action card shown in the OpenTV row (settings / refresh). */
data class MenuAction(
    val id: String,
    val title: String,
    val subtitle: String
) : Serializable
