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
    val number: Int = 0,
    /** Catch-up (5.0.0): the channel's timeshift support, from the M3U
     * catchup / catchup-days / catchup-source attributes (per channel,
     * or playlist-wide from the #EXTM3U header). All null = no
     * catch-up for this channel. */
    val catchupType: String? = null,
    val catchupDays: Int? = null,
    val catchupSource: String? = null
) : Serializable

/** A small action card shown in the OpenTV row (settings / refresh). */
data class MenuAction(
    val id: String,
    val title: String,
    val subtitle: String
) : Serializable
