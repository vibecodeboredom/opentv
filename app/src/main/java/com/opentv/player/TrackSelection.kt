package com.opentv.player

import java.util.Locale

/**
 * 7.3.0 — Audio & picture: audio track and subtitle selection.
 * Pure logic — no Android or ExoPlayer classes — so the labels and
 * lookups are unit-testable; PlaybackActivity owns the player, the
 * track overrides and the dialogs.
 */
object TrackSelection {

    /** One selectable track: its index into the player's track
     * groups, its index inside that group, its display label and
     * its ISO 639 language code (null when the stream carries
     * none). */
    data class TrackChoice(
        val groupIndex: Int,
        val trackIndex: Int,
        val label: String,
        val language: String?
    )

    /** Display name for a track: the stream's own label wins when it
     * is non-blank (HLS NAME attributes are usually "English" or
     * "Español"); otherwise the language code becomes a language
     * name; otherwise "Track N". */
    fun displayName(language: String?, label: String?, fallbackIndex: Int): String {
        if (!label.isNullOrBlank()) return label.trim()
        languageDisplayName(language)?.let { return it }
        return "Track ${fallbackIndex + 1}"
    }

    /** A language code as a display name ("eng" -> "English",
     * "es" -> "Spanish"). Null when the code is null, blank or
     * unknown. Two-letter codes go through BCP 47; three-letter
     * codes (what HLS and ExoPlayer report) are matched against the
     * available locales' ISO-3 codes. */
    fun languageDisplayName(code: String?): String? {
        val trimmed = code?.trim().orEmpty()
        if (trimmed.isEmpty()) return null
        if (trimmed.length == 2) {
            val name = Locale.forLanguageTag(trimmed).displayLanguage
            if (name.isNotBlank() && !name.equals(trimmed, ignoreCase = true)) return name
        }
        // Three-letter code: find a locale whose ISO-3 language
        // matches. getISO3Language throws for locales without one,
        // so each lookup is guarded.
        val lower = trimmed.lowercase()
        for (locale in Locale.getAvailableLocales()) {
            val iso3 = runCatching { locale.isO3Language }.getOrNull() ?: continue
            if (iso3.equals(lower, ignoreCase = true)) {
                val name = locale.displayLanguage
                if (name.isNotBlank()) return name
            }
        }
        return null
    }

    /** Menu label for the audio track: "Auto" when nothing is
     * chosen, else the chosen language's display name. */
    fun audioLabel(language: String?): String =
        languageDisplayName(language) ?: "Auto"

    /** Menu label for subtitles: "Off" when nothing is chosen, else
     * the chosen language's display name. */
    fun subtitleLabel(language: String?): String =
        languageDisplayName(language) ?: "Off"

    /** The choice for a language code, or null when no listed track
     * carries it. */
    fun findByLanguage(choices: List<TrackChoice>, language: String?): TrackChoice? {
        if (language == null) return null
        return choices.firstOrNull {
            it.language?.equals(language, ignoreCase = true) == true
        }
    }
}
