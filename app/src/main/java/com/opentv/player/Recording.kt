package com.opentv.player

/**
 * One local recording: a channel stream downloaded with media3's
 * DownloadManager into the app's own storage.
 */
data class Recording(
    /** DownloadManager id (the channel id, sanitized). */
    val id: String,
    val channelName: String,
    val uri: String,
    /** When the recording started, epoch millis. */
    val startedAtMillis: Long,
    /** Bytes downloaded so far. */
    val bytesDownloaded: Long,
    /** True while the download is still running (recording in progress). */
    val inProgress: Boolean
) {
    companion object {
        /** A download id is derived from the channel id; keep it filesystem-safe. */
        fun idForChannel(channelId: String): String =
            "rec_" + channelId.replace(Regex("[^A-Za-z0-9_.-]"), "_").take(80)
    }
}
