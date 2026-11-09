package com.opentv.player

import java.util.Calendar
import java.util.TimeZone

/**
 * Catch-up TV (5.0.0): replay a programme that already aired, from
 * the guide, when the playlist says the channel supports it.
 *
 * M3U catch-up attributes (per channel on #EXTINF, or playlist-wide
 * on the #EXTM3U header line — a channel's own values win):
 *   catchup="default|shift|append|flussonic|xc|fs"
 *   catchup-days="7"
 *   catchup-source="https://server/path/{utc}-{lutc}.m3u8"
 *
 * URL forms:
 * - catchup-source template: placeholders are substituted with the
 *   programme's window — {utc} = start (unix seconds), {lutc} = stop
 *   (unix seconds), {Y} {m} {d} {H} {M} {S} = the programme START in
 *   UTC (year, month, day, hour, minute, second), ${start} / ${end}
 *   = start / stop unix seconds (used by some providers). A template
 *   with no recognised placeholder is used verbatim.
 * - catchup="default" (and any type with no confident standard form —
 *   "shift", "flussonic", "xc", "fs"): the programme window is
 *   appended as query params, "?utc=<start>&lutc=<stop>" ("&" when the
 *   URL already has a query string). This is the most widely used
 *   form, so the unknown types fall back to it rather than guessing
 *   a provider-specific URL shape.
 * - catchup="append": the window is appended to the path as
 *   "/<start>-<stop>". A URL that already has a query string falls
 *   back to the default query form — a path suffix after a query
 *   would be invalid.
 *
 * Availability: the programme must already be over, and its start
 * must be within catchup-days of now (7 days when catchup-days is
 * absent). Anything else returns null — never a crash, never a
 * half-built URL.
 */
object CatchUp {

    /** Default archive depth when catchup-days is absent. */
    const val DEFAULT_CATCHUP_DAYS = 7

    /** True when the channel declares any catch-up support at all. */
    fun hasCatchup(channel: Channel): Boolean =
        channel.catchupSource != null ||
            channel.catchupType != null ||
            channel.catchupDays != null

    /** True when this programme can be replayed right now. */
    fun isAvailable(
        channel: Channel,
        programme: EpgParser.Programme,
        nowMillis: Long = System.currentTimeMillis()
    ): Boolean {
        if (!hasCatchup(channel)) return false
        if (programme.stopMillis > nowMillis) return false
        val days = channel.catchupDays?.takeIf { it > 0 } ?: DEFAULT_CATCHUP_DAYS
        val oldestStart = nowMillis - days * 24L * 60 * 60 * 1000
        return programme.startMillis >= oldestStart
    }

    /** The catch-up stream URL for this programme, or null when
     * catch-up is unavailable. Never throws on a malformed template
     * or bad attributes — those just mean "no catch-up". */
    fun catchupUrl(
        channel: Channel,
        programme: EpgParser.Programme,
        nowMillis: Long = System.currentTimeMillis()
    ): String? {
        if (!isAvailable(channel, programme, nowMillis)) return null
        return try {
            buildUrl(channel, programme)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun buildUrl(channel: Channel, programme: EpgParser.Programme): String {
        val startSec = programme.startMillis / 1000
        val stopSec = programme.stopMillis / 1000
        val template = channel.catchupSource?.takeIf { it.isNotBlank() }
        if (template != null) return substitute(template, programme)
        return when (channel.catchupType?.lowercase()) {
            "append" -> appendUrl(channel.url, startSec, stopSec)
            else -> defaultUrl(channel.url, startSec, stopSec)
        }
    }

    private fun defaultUrl(url: String, startSec: Long, stopSec: Long): String {
        val sep = if (url.contains('?')) "&" else "?"
        return "$url${sep}utc=$startSec&lutc=$stopSec"
    }

    private fun appendUrl(url: String, startSec: Long, stopSec: Long): String {
        if (url.contains('?')) return defaultUrl(url, startSec, stopSec)
        return url.trimEnd('/') + "/$startSec-$stopSec"
    }

    private fun substitute(template: String, programme: EpgParser.Programme): String {
        val startSec = programme.startMillis / 1000
        val stopSec = programme.stopMillis / 1000
        var out = template
        // Only substitute a placeholder the template actually uses —
        // the old code ran ten replaces (and six String.format calls)
        // on every template, even one with a single {utc}.
        if (out.contains("{utc}")) out = out.replace("{utc}", startSec.toString())
        if (out.contains("{lutc}")) out = out.replace("{lutc}", stopSec.toString())
        if (out.contains("\${start}")) out = out.replace("\${start}", startSec.toString())
        if (out.contains("\${end}")) out = out.replace("\${end}", stopSec.toString())
        if (out.contains("{Y}") || out.contains("{m}") || out.contains("{d}") ||
            out.contains("{H}") || out.contains("{M}") || out.contains("{S}")
        ) {
            val cal = utcCalendar()
            cal.timeInMillis = programme.startMillis
            if (out.contains("{Y}")) out = out.replace("{Y}", pad4(cal.get(Calendar.YEAR)))
            if (out.contains("{m}")) out = out.replace("{m}", pad2(cal.get(Calendar.MONTH) + 1))
            if (out.contains("{d}")) out = out.replace("{d}", pad2(cal.get(Calendar.DAY_OF_MONTH)))
            if (out.contains("{H}")) out = out.replace("{H}", pad2(cal.get(Calendar.HOUR_OF_DAY)))
            if (out.contains("{M}")) out = out.replace("{M}", pad2(cal.get(Calendar.MINUTE)))
            if (out.contains("{S}")) out = out.replace("{S}", pad2(cal.get(Calendar.SECOND)))
        }
        return out
    }

    /** Zero-padded numbers without String.format (which builds a
     * Formatter and parses the format string on every call) — identical
     * output to "%02d" / "%04d" for non-negative values. */
    private fun pad2(v: Int): String = if (v < 10) "0$v" else v.toString()

    private fun pad4(v: Int): String = when {
        v < 10 -> "000$v"
        v < 100 -> "00$v"
        v < 1000 -> "0$v"
        else -> v.toString()
    }

    /** One UTC Calendar per thread, reused like GuideData's local one. */
    private val utcCalendarThreadLocal = ThreadLocal<Calendar>()

    private fun utcCalendar(): Calendar {
        val cal = utcCalendarThreadLocal.get()
            ?: Calendar.getInstance(TimeZone.getTimeZone("UTC"))
                .also { utcCalendarThreadLocal.set(it) }
        return cal
    }
}
