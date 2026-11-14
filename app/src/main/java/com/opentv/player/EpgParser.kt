package com.opentv.player

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal XMLTV parser. Only keeps programme titles with start/stop times,
 * keyed by channel id, so the player can show "Now" / "Next".
 *
 * Single-pass streaming scan over the XML text: the old version built a
 * full DOM tree (javax.xml) just to walk two element types, which for a
 * multi-MB guide meant the raw string AND a DOM of several times its size
 * were live at once, plus the tree-build time on every cold parse. This
 * scanner walks the text once with an element stack, captures only
 * <programme> (channel/start/stop + first <title>) and <channel>
 * (id + first <display-name>), and never materialises the document.
 * Pure Kotlin string code, so it behaves identically on-device and in
 * plain JVM unit tests (Android's XmlPullParser is a stub there).
 *
 * Malformed input stays all-or-nothing like the DOM version: a fatal
 * well-formedness error (unterminated tag/quote/comment/CDATA, a
 * mismatched end tag, an undefined entity) discards everything and the
 * caller just sees no guide data.
 */
object EpgParser {

    data class Programme(
        val channelId: String,
        val startMillis: Long,
        val stopMillis: Long,
        val title: String
    )

    private val xmltvDate = SimpleDateFormat("yyyyMMddHHmmss Z", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    /**
     * Single-pass parse: returns the programmes map AND the channel
     * id -> display-name map from one pass. Parsing the XML twice
     * (once for programmes, once for names) was doubling the guide's
     * load time, so callers that need both should use this.
     */
    fun parseWithNames(xml: String): Pair<Map<String, List<Programme>>, Map<String, String>> {
        val scanner = Scanner(xml)
        val ok = try {
            scanner.scan()
        } catch (ignored: Exception) {
            // Mirror the DOM version's catch-all: EPG is best-effort,
            // any failure just means no guide data.
            false
        }
        return if (ok) {
            scanner.programmes to scanner.names
        } else {
            emptyMap<String, List<Programme>>() to emptyMap()
        }
    }

    fun parse(xml: String): Map<String, List<Programme>> = parseWithNames(xml).first

    /** Channel id -> display name, from the <channel> elements. Used to match
     * a playlist channel to its EPG data by name when the ids don't align. */
    fun channelDisplayNames(xml: String): Map<String, String> = parseWithNames(xml).second

    /**
     * Returns the programme airing now and the next one, for a channel.
     * Single linear scan, no sort: XMLTV lists a channel's programmes in
     * chronological order, so the first programme containing now is the
     * current one and the first starting at/after now is the next.
     */
    fun nowAndNext(
        programmes: List<Programme>?,
        nowMillis: Long = System.currentTimeMillis()
    ): Pair<Programme?, Programme?> {
        if (programmes.isNullOrEmpty()) return null to null
        var now: Programme? = null
        var next: Programme? = null
        for (p in programmes) {
            if (now == null && nowMillis in p.startMillis until p.stopMillis) now = p
            if (next == null && p.startMillis >= nowMillis) next = p
            if (now != null && next != null) break
        }
        return now to next
    }

    private fun parseDate(raw: String?): Long {
        if (raw.isNullOrBlank()) return 0L
        return try {
            // XMLTV dates look like "20261107200000 +0000".
            xmltvDate.parse(raw.trim())?.time ?: 0L
        } catch (ignored: Exception) {
            0L
        }
    }

    /**
     * The streaming scanner. Walks the XML once, keeping an element-name
     * stack for well-formedness and depth-matched state for the one
     * programme / channel currently open. [scan] returns false on a
     * fatal well-formedness error (the caller discards both maps, like
     * the DOM version did when its parse threw).
     */
    private class Scanner(private val xml: String) {
        val programmes = mutableMapOf<String, MutableList<Programme>>()
        val names = mutableMapOf<String, String>()

        private var pos = 0
        private val len = xml.length
        private val stack = ArrayList<String>()

        // The open <programme> (progDepth < 0 = none open). Depths are
        // the stack size just after the element was pushed, so a nested
        // element of the same name can't close it early.
        private var progDepth = -1
        private var progChannel = ""
        private var progStart: String? = null
        private var progStop: String? = null
        private var progTitle: String? = null

        // The open <channel>, same pattern.
        private var chanDepth = -1
        private var chanId = ""
        private var chanName: String? = null

        // Text capture for the first <title> / <display-name> inside the
        // open element: while captureDepth > 0, all character data (the
        // element's own and any nested element's, matching textContent)
        // goes to captureBuf until the element at captureDepth closes.
        private var captureDepth = -1
        private var captureForProgramme = false
        private val captureBuf = StringBuilder()

        fun scan(): Boolean {
            while (pos < len) {
                val lt = xml.indexOf('<', pos)
                if (lt < 0) {
                    if (!text(xml.substring(pos))) return false
                    pos = len
                    break
                }
                if (lt > pos && !text(xml.substring(pos, lt))) return false
                if (lt + 1 >= len) return false
                pos = when (xml[lt + 1]) {
                    '?' -> skipPi(lt)
                    '!' -> skipBang(lt)
                    '/' -> endTag(lt)
                    else -> startTag(lt)
                }
                if (pos < 0) return false
            }
            // Unclosed elements at EOF are malformed.
            return stack.isEmpty()
        }

        /** Character data: entity-decode into the capture, if any.
         * False on an undefined / malformed entity reference. */
        private fun text(raw: String): Boolean {
            if (captureDepth < 0 || raw.isEmpty()) return true
            if (!raw.contains('&')) {
                captureBuf.append(raw)
                return true
            }
            var i = 0
            while (i < raw.length) {
                val amp = raw.indexOf('&', i)
                if (amp < 0) {
                    captureBuf.append(raw, i, raw.length)
                    break
                }
                captureBuf.append(raw, i, amp)
                val semi = raw.indexOf(';', amp + 1)
                if (semi < 0) return false
                val decoded = decodeEntity(raw.substring(amp + 1, semi)) ?: return false
                captureBuf.append(decoded)
                i = semi + 1
            }
            return true
        }

        private fun decodeEntity(entity: String): String? = when (entity) {
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            else -> {
                val code = when {
                    entity.startsWith("#x") || entity.startsWith("#X") ->
                        entity.substring(2).toIntOrNull(16)
                    entity.startsWith("#") -> entity.substring(1).toIntOrNull()
                    else -> null
                }
                // toChars throws on an out-of-range code point; the
                // caller's all-or-nothing catch treats that as fatal.
                code?.let { String(Character.toChars(it)) }
            }
        }

        /** `<? ... ?>` — returns the index just past it, or -1. */
        private fun skipPi(lt: Int): Int {
            val end = xml.indexOf("?>", lt + 2)
            return if (end < 0) -1 else end + 2
        }

        /** `<!-- comment -->`, `<![CDATA[ ... ]]>`, or a `<!...>`
         * declaration (DOCTYPE) — index just past it, or -1. */
        private fun skipBang(lt: Int): Int {
            if (xml.startsWith("<!--", lt)) {
                val end = xml.indexOf("-->", lt + 4)
                return if (end < 0) -1 else end + 3
            }
            if (xml.startsWith("<![CDATA[", lt)) {
                val end = xml.indexOf("]]>", lt + 9)
                if (end < 0) return -1
                // CDATA content is literal character data.
                if (captureDepth >= 0) captureBuf.append(xml, lt + 9, end)
                return end + 3
            }
            // DOCTYPE and friends: skip to '>', stepping over an
            // internal subset in [ ... ] which may itself contain '>'.
            var i = lt + 2
            while (i < len) {
                when (xml[i]) {
                    '[' -> {
                        val close = xml.indexOf(']', i + 1)
                        if (close < 0) return -1
                        i = close + 1
                    }
                    '>' -> return i + 1
                    else -> i++
                }
            }
            return -1
        }

        /** `</name>` — pops the stack and finishes any capture /
         * programme / channel the end tag closes. Index past '>', -1
         * on a mismatch. */
        private fun endTag(lt: Int): Int {
            var i = lt + 2
            val nameStart = i
            while (i < len && !xml[i].isWhitespace() && xml[i] != '>') i++
            val name = xml.substring(nameStart, i)
            while (i < len && xml[i].isWhitespace()) i++
            if (i >= len || xml[i] != '>') return -1
            if (stack.isEmpty() || stack[stack.size - 1] != name) return -1
            // A capture ends when its own element closes (it is the
            // element on top of the stack right now).
            if (captureDepth == stack.size) {
                val value = captureBuf.toString()
                if (captureForProgramme) progTitle = value else chanName = value
                captureDepth = -1
                captureBuf.setLength(0)
            }
            if (name == "programme" && progDepth == stack.size) finishProgramme()
            if (name == "channel" && chanDepth == stack.size) finishChannel()
            stack.removeAt(stack.size - 1)
            return i + 1
        }

        private fun finishProgramme() {
            val title = progTitle?.trim()
            val start = parseDate(progStart)
            val stop = parseDate(progStop)
            if (progChannel.isNotEmpty() && !title.isNullOrEmpty() && stop > start) {
                programmes.getOrPut(progChannel) { mutableListOf() } += Programme(
                    channelId = progChannel,
                    startMillis = start,
                    stopMillis = stop,
                    title = title
                )
            }
            progDepth = -1
            progChannel = ""
            progStart = null
            progStop = null
            progTitle = null
        }

        private fun finishChannel() {
            val name = chanName?.trim()
            if (chanId.isNotEmpty() && !name.isNullOrEmpty()) {
                names[chanId] = name
            }
            chanDepth = -1
            chanId = ""
            chanName = null
        }

        /** `<name attr="v" ...>` or `<name ... />` — index just past
         * '>', or -1 on malformed markup. */
        private fun startTag(lt: Int): Int {
            var i = lt + 1
            val nameStart = i
            while (i < len && !xml[i].isWhitespace() && xml[i] != '>' && xml[i] != '/') i++
            if (i == nameStart) return -1
            val name = xml.substring(nameStart, i)
            var selfClosing = false
            var channel: String? = null
            var start: String? = null
            var stop: String? = null
            var id: String? = null
            // Attribute scan: only the attributes the parser uses are
            // decoded and kept; the rest are skipped over.
            while (true) {
                while (i < len && xml[i].isWhitespace()) i++
                if (i >= len) return -1
                when (xml[i]) {
                    '>' -> {
                        i++
                        break
                    }
                    '/' -> {
                        if (i + 1 >= len || xml[i + 1] != '>') return -1
                        selfClosing = true
                        i += 2
                        break
                    }
                    else -> {
                        val attrStart = i
                        while (i < len && xml[i] != '=' && !xml[i].isWhitespace() &&
                            xml[i] != '>' && xml[i] != '/'
                        ) i++
                        val attrName = xml.substring(attrStart, i)
                        while (i < len && xml[i].isWhitespace()) i++
                        var value = ""
                        if (i < len && xml[i] == '=') {
                            i++
                            while (i < len && xml[i].isWhitespace()) i++
                            if (i >= len || (xml[i] != '"' && xml[i] != '\'')) return -1
                            val quote = xml[i]
                            val valueStart = ++i
                            val valueEnd = xml.indexOf(quote, valueStart)
                            if (valueEnd < 0) return -1
                            value = decodeAttr(xml.substring(valueStart, valueEnd))
                                ?: return -1
                            i = valueEnd + 1
                        }
                        when (name) {
                            "programme" -> when (attrName) {
                                "channel" -> channel = value
                                "start" -> start = value
                                "stop" -> stop = value
                            }
                            "channel" -> if (attrName == "id") id = value
                        }
                    }
                }
            }
            // Element-open bookkeeping. A self-closing element opens
            // and closes at once, so it is never pushed; opening a
            // programme / channel just records it and finishes it
            // straight away (with no title / name it adds nothing).
            val depth = stack.size + 1
            when (name) {
                "programme" -> if (progDepth < 0) {
                    progChannel = channel ?: ""
                    progStart = start
                    progStop = stop
                    progTitle = null
                    progDepth = depth
                    if (selfClosing) finishProgramme()
                }
                "channel" -> if (chanDepth < 0) {
                    chanId = id ?: ""
                    chanName = null
                    chanDepth = depth
                    if (selfClosing) finishChannel()
                }
                "title" -> if (progDepth > 0 && progTitle == null && captureDepth < 0) {
                    if (selfClosing) {
                        progTitle = ""
                    } else {
                        captureDepth = depth
                        captureForProgramme = true
                        captureBuf.setLength(0)
                    }
                }
                "display-name" -> if (chanDepth > 0 && chanName == null && captureDepth < 0) {
                    if (selfClosing) {
                        chanName = ""
                    } else {
                        captureDepth = depth
                        captureForProgramme = false
                        captureBuf.setLength(0)
                    }
                }
            }
            if (!selfClosing) stack.add(name)
            return i
        }

        /** Entity-decode an attribute value; null on a bad reference. */
        private fun decodeAttr(raw: String): String? {
            if (!raw.contains('&')) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val amp = raw.indexOf('&', i)
                if (amp < 0) {
                    out.append(raw, i, raw.length)
                    break
                }
                out.append(raw, i, amp)
                val semi = raw.indexOf(';', amp + 1)
                if (semi < 0) return null
                val decoded = decodeEntity(raw.substring(amp + 1, semi)) ?: return null
                out.append(decoded)
                i = semi + 1
            }
            return out.toString()
        }
    }
}
