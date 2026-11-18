package com.opentv.player

/**
 * Channel-number entry: the user types digits on the remote and the
 * app jumps to that channel once the entry settles. 7.0.0: the jump
 * lives in the guide (focus the channel's row) and in playback
 * (switch to the channel). Pure state — no Android classes — so the
 * digit handling is unit-testable; the activities own the timeout
 * that decides when an entry has settled.
 */
class ChannelNumberEntry(private val maxDigits: Int = 4) {

    private val digits = StringBuilder()

    /** Append one digit (0–9) and return the entry so far ("12"). */
    fun press(digit: Int): String {
        require(digit in 0..9) { "digit must be 0-9, was $digit" }
        if (digits.length < maxDigits) digits.append(digit)
        return digits.toString()
    }

    /** The entry so far, or "" when nothing is typed. */
    fun text(): String = digits.toString()

    /** The typed number, or null when empty / not a number. */
    fun value(): Int? = digits.toString().toIntOrNull()

    /** True when no digits have been typed. */
    fun isEmpty(): Boolean = digits.isEmpty()

    /** Clear the entry (after a jump, or when the user backs out). */
    fun clear() {
        digits.clear()
    }
}
