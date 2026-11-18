package com.opentv.player

/**
 * 8.3.0 — Pure helpers for the recordings list: sorting +
 * filtering for the recordings screen, and picking which finished
 * recordings the auto-delete setting should remove. Kept free of
 * Android classes so it is unit-testable; RecordingManager does
 * the actual deleting.
 */
object RecordingLibrary {

    /** Sort orders for the recordings list. */
    enum class Sort { NEWEST, OLDEST, LARGEST, SMALLEST, NAME }

    /** Filters for the recordings list. */
    enum class Filter { ALL, IN_PROGRESS, FINISHED }

    /** Sort + filter a recordings list. The sort is stable: ties
     * fall back to the recording id, so the order never shuffles
     * between reloads. */
    fun sortAndFilter(
        recordings: List<Recording>,
        sort: Sort,
        filter: Filter
    ): List<Recording> {
        val filtered = when (filter) {
            Filter.ALL -> recordings
            Filter.IN_PROGRESS -> recordings.filter { it.inProgress }
            Filter.FINISHED -> recordings.filter { !it.inProgress }
        }
        return when (sort) {
            Sort.NEWEST -> filtered.sortedWith(
                compareByDescending<Recording> { it.startedAtMillis }.thenBy { it.id }
            )
            Sort.OLDEST -> filtered.sortedWith(
                compareBy<Recording> { it.startedAtMillis }.thenBy { it.id }
            )
            Sort.LARGEST -> filtered.sortedWith(
                compareByDescending<Recording> { it.bytesDownloaded }.thenBy { it.id }
            )
            Sort.SMALLEST -> filtered.sortedWith(
                compareBy<Recording> { it.bytesDownloaded }.thenBy { it.id }
            )
            Sort.NAME -> filtered.sortedWith(
                compareBy<Recording> { it.channelName.lowercase() }.thenBy { it.id }
            )
        }
    }

    /**
     * The finished recordings older than [keepDays] days — the ones
     * the auto-delete setting removes. A keep of 0 or less means
     * "off" and selects nothing. In-progress recordings are never
     * selected, however old they are (a very long recording must
     * not be deleted under itself), and a recording with no start
     * time recorded is never selected either — its age is unknown,
     * so deleting it would be a guess.
     */
    fun oldRecordings(
        recordings: List<Recording>,
        keepDays: Int,
        nowMillis: Long
    ): List<Recording> {
        if (keepDays <= 0) return emptyList()
        val cutoff = nowMillis - keepDays * 24L * 60 * 60 * 1000
        return recordings.filter {
            !it.inProgress && it.startedAtMillis > 0 && it.startedAtMillis < cutoff
        }
    }
}
