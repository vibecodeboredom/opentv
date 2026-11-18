package com.opentv.player

/**
 * 8.1.0 — Channel library: custom channel groups. The playlist
 * itself is never edited — the user's renames, ordering and
 * per-channel moves live here as overrides, applied on top of the
 * playlist's own group-title values.
 *
 * - renames: playlist group name -> display name (what the sidebar
 *   and group screens show).
 * - order: playlist group names in the user's order. Groups missing
 *   from it keep their playlist order, after the ordered ones.
 * - moves: channel id -> playlist group name. A moved channel shows
 *   under the target group instead of the one its playlist entry
 *   carries.
 *
 * The encode / decode is pure (newline-joined lines, "\u0001"
 * between key and value — the same separator the playlist list
 * uses, since a channel id can contain '=' from a URL query) so it
 * is unit-testable; only PlaylistRepository touches
 * SharedPreferences. Group names are cleaned of newlines/tabs when
 * stored, so a line always stays one entry.
 */
data class GroupOverrides(
    val renames: Map<String, String> = emptyMap(),
    val order: List<String> = emptyList(),
    val moves: Map<String, String> = emptyMap()
) {

    companion object {

        /** One group as the UI sees it: its playlist key, its
         * display name, and its (visible) channels. */
        data class GroupView(
            val key: String,
            val displayName: String,
            val channels: List<Channel>
        )

        /** The group a channel belongs to once moves are applied. */
        fun effectiveGroup(channel: Channel, overrides: GroupOverrides): String =
            overrides.moves[channel.id] ?: channel.group

        /** The display name of a group once renames are applied. */
        fun displayGroup(group: String, overrides: GroupOverrides): String =
            overrides.renames[group] ?: group

        /**
         * The groups for a channel list, in display order: the
         * user's order first (by playlist group key), then any
         * group missing from it in playlist order (first
         * appearance). Each group carries its display name and its
         * channels (in playlist order). Channels whose effective
         * group is blank land in an "Ungrouped" group.
         */
        fun effectiveGroups(
            channels: List<Channel>,
            overrides: GroupOverrides
        ): List<GroupView> {
            val byKey = linkedMapOf<String, MutableList<Channel>>()
            for (ch in channels) {
                val key = effectiveGroup(ch, overrides).ifBlank { "Ungrouped" }
                byKey.getOrPut(key) { mutableListOf() } += ch
            }
            val orderedKeys = overrides.order.filter { it in byKey } +
                byKey.keys.filter { it !in overrides.order }
            return orderedKeys.map { key ->
                GroupView(key, displayGroup(key, overrides), byKey.getValue(key))
            }
        }

        /**
         * The full group order after moving [key] one step up
         * (towards the top) or down. The order is built from the
         * stored order plus any group missing from it (in playlist
         * order), so a group that was never ordered can still be
         * moved. Moving the first group up or the last group down
         * is a no-op.
         */
        fun reorderedOrder(
            order: List<String>,
            allGroups: List<String>,
            key: String,
            up: Boolean
        ): List<String> {
            val full = (order.filter { it in allGroups } +
                allGroups.filter { it !in order }).toMutableList()
            val idx = full.indexOf(key)
            if (idx < 0) return full
            val swap = if (up) idx - 1 else idx + 1
            if (swap < 0 || swap >= full.size) return full
            val moved = full[idx]
            full[idx] = full[swap]
            full[swap] = moved
            return full
        }

        /** A group name safe to store: no newlines or tabs, trimmed. */
        fun cleanGroupName(name: String): String =
            name.replace("\n", " ").replace("\r", " ")
                .replace("\u0001", " ").replace("\t", " ").trim()

        private const val SEP = "\u0001"

        /** Encode a string map as one entry per line, "key\u0001value". */
        fun encodeMap(map: Map<String, String>): String =
            map.entries.joinToString("\n") { "${it.key}$SEP${it.value}" }

        /** Decode lines of "key\u0001value" back into a map. Lines
         * without the separator, or with a blank key or value, are
         * skipped — a stray line never breaks the rest. */
        fun decodeMap(raw: String): Map<String, String> {
            if (raw.isBlank()) return emptyMap()
            return raw.split("\n").mapNotNull { line ->
                val idx = line.indexOf(SEP)
                if (idx <= 0) null
                else {
                    val key = line.substring(0, idx)
                    val value = line.substring(idx + 1)
                    if (key.isBlank() || value.isBlank()) null else key to value
                }
            }.toMap()
        }

        /** Encode the order as one group name per line. */
        fun encodeOrder(order: List<String>): String =
            order.joinToString("\n")

        /** Decode the order, dropping blank lines and duplicates
         * (first occurrence wins). */
        fun decodeOrder(raw: String): List<String> {
            if (raw.isBlank()) return emptyList()
            val seen = mutableSetOf<String>()
            return raw.split("\n").map { it.trim() }
                .filter { it.isNotEmpty() && seen.add(it) }
        }
    }
}
