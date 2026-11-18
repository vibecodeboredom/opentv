package com.opentv.player

import android.content.Context

/**
 * 7.3.1 — Audio & picture patch: track-selection persistence per
 * channel. In 7.3.0 the audio / subtitle choice lived only in the
 * viewing session — returning to a channel reset it to Auto / Off
 * every time. Now the choice is remembered per channel id, so a
 * channel re-opens with the language and captions the user picked
 * for it.
 *
 * Storage is two SharedPreferences keys per channel id: one for
 * audio, one for subtitles. A key's presence means the user picked
 * something for that channel; its value is the language code, or a
 * sentinel when the pick was "Auto" (audio) or "Off" (subtitles) —
 * so an explicit Auto / Off is remembered, and a channel with no
 * stored pick is told apart from one the user set to Auto / Off.
 * The encode / decode is pure and unit-tested; only load / save
 * touch SharedPreferences.
 */
object TrackPrefs {

    private const val PREFS_NAME = "track_prefs"

    /** Stored audio value meaning the user picked Auto. */
    const val AUDIO_AUTO = "auto"

    /** Stored subtitle value meaning the user picked Off. */
    const val SUBTITLE_OFF = "off"

    /** What is stored for one channel: the language to use, or null
     * for Auto (audio) / Off (subtitles), and whether the user ever
     * picked anything for that channel at all. */
    data class StoredChoice(
        val audioLanguage: String?,
        val audioSet: Boolean,
        val subtitleLanguage: String?,
        val subtitleSet: Boolean
    )

    fun audioKey(channelId: String): String = "a:$channelId"

    fun subtitleKey(channelId: String): String = "s:$channelId"

    /** Encode a language pick for storage: the code, or the
     * sentinel when the pick is "none" (Auto for audio, Off for
     * subtitles). A blank code counts as none. */
    fun encodeChoice(language: String?, noneValue: String): String =
        language?.takeIf { it.isNotBlank() } ?: noneValue

    /** Decode a stored value: the language and whether a pick was
     * ever stored. Null (no key) is "never picked"; the sentinel
     * is a pick of "none"; anything else is the language code. */
    fun decodeChoice(value: String?, noneValue: String): Pair<String?, Boolean> =
        when {
            value == null -> null to false
            value == noneValue -> null to true
            else -> value to true
        }

    /** The stored choice for a channel, or an all-unset choice when
     * nothing was ever picked for it. */
    fun load(context: Context, channelId: String): StoredChoice {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val (audioLanguage, audioSet) =
            decodeChoice(prefs.getString(audioKey(channelId), null), AUDIO_AUTO)
        val (subtitleLanguage, subtitleSet) =
            decodeChoice(prefs.getString(subtitleKey(channelId), null), SUBTITLE_OFF)
        return StoredChoice(audioLanguage, audioSet, subtitleLanguage, subtitleSet)
    }

    /** Remember the audio pick for a channel — a language code, or
     * null for Auto. */
    fun saveAudio(context: Context, channelId: String, language: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(audioKey(channelId), encodeChoice(language, AUDIO_AUTO))
            .apply()
    }

    /** Remember the subtitle pick for a channel — a language code,
     * or off = true for Off. */
    fun saveSubtitle(context: Context, channelId: String, language: String?, off: Boolean) {
        val value = if (off) SUBTITLE_OFF else encodeChoice(language, SUBTITLE_OFF)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(subtitleKey(channelId), value)
            .apply()
    }

    /** 8.0.0 — Every stored track pick, raw ("a:<id>" / "s:<id>"
     * -> stored value), for the backup. */
    fun allEntries(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            val str = value as? String ?: return@mapNotNull null
            key to str
        }.toMap()
    }

    /** 8.0.0 — Replace all stored track picks wholesale (backup
     * restore). */
    fun restoreAll(context: Context, entries: Map<String, String>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        editor.clear()
        for ((key, value) in entries) {
            editor.putString(key, value)
        }
        editor.apply()
    }
}
