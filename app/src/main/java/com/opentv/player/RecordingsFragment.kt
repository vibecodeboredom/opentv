package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch
import java.text.DateFormat

/**
 * The recordings screen: every local recording as a card, with the total
 * storage used in the title. Selecting a recording offers Play / Delete.
 *
 * Combine: Nova's scheduled row + Continue watching row + storage-guard
 * notes, Juniper's sort/filter controls + age-based auto-delete + true
 * on-disk total. The scheduled store is Nova's (already wired into
 * the guide); the start alarms are exact (Juniper's setAlarmClock
 * win, see RecordingManager).
 */
@OptIn(UnstableApi::class)
class RecordingsFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.recordings)
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Recording -> showRecordingOptions(item)
                // 9.0.0 (Nova): a Continue watching card is its
                // recording, for options purposes — Play resumes
                // it (PlaybackActivity owns the resume itself).
                is ContinueWatchingEntry -> showRecordingOptions(item.recording)
                is PlaylistRepository.ScheduledRecording ->
                    showScheduledRecordingOptions(item)
                // 8.3.0 (Juniper): sort / filter controls.
                is MenuAction -> onMenuAction(item)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        loadRecordings()
    }

    override fun onResume() {
        super.onResume()
        loadRecordings()
    }

    private fun loadRecordings() {
        // 9.2.0 (Nova): the storage guard's recordings-screen
        // pass, before listing, so what this screen shows is
        // already within the cap. No-op when the cap is off.
        RecordingManager.enforceRecordingStorageCap(requireContext())
        val all = RecordingManager.listRecordings(requireContext())
        // 8.3.0 (Juniper): auto-delete — finished recordings older
        // than the keep setting go now, from the list already
        // fetched (no second DownloadIndex query). The deleted
        // ones are dropped from the fetched list too, so the screen
        // never shows a recording that is already gone.
        val keepDays = AppSettings.autoDeleteRecordingsDays(requireContext())
        var recordings = all
        if (keepDays > 0) {
            val deleted = RecordingManager.deleteOldRecordings(
                requireContext(), all, keepDays
            )
            if (deleted.isNotEmpty()) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.recordings_old_deleted, deleted.size),
                    Toast.LENGTH_LONG
                ).show()
                val deletedIds = deleted.map { it.id }.toSet()
                recordings = all.filter { it.id !in deletedIds }
            }
        }
        // 8.2.0 (Nova): scheduled recordings get their own row at
        // the top — they are not downloads yet, so they don't count
        // toward the title's size line, but they belong on this
        // screen: it's where a user looks to manage them.
        val scheduled = PlaylistRepository.scheduledRecordings(requireContext())
        // 8.3.1 (Juniper): the total is the true on-disk figure
        // (diskBytesUsed), not the sum of the index's claimed
        // bytes — the cache's LRU evictor may have dropped segments
        // of old recordings, which the index never learns.
        val used = RecordingManager.diskBytesUsed(requireContext())
        // 9.2.0 (Nova): a storage-guard run that freed space
        // leaves a one-line note, shown once in this title and
        // then cleared — the header is where this screen already
        // reports its total size.
        val freedNote = AppSettings.lastAutoFreedBytes(requireContext())
        if (freedNote > 0L) {
            AppSettings.setLastAutoFreedBytes(requireContext(), 0L)
        }
        // 9.2.1 (Nova): the same one-shot pattern for a guard
        // pass that stayed over the cap because the remaining
        // recordings belong to favorite channels — read here,
        // cleared here, so it is reported exactly once.
        val deleteBlocked = AppSettings.lastAutoDeleteBlocked(requireContext())
        if (deleteBlocked) {
            AppSettings.setLastAutoDeleteBlocked(requireContext(), false)
        }
        val baseTitle = if (recordings.isEmpty()) {
            getString(R.string.recordings)
        } else {
            getString(
                R.string.recordings_with_size,
                recordings.size,
                RecordingManager.formatBytes(used)
            )
        }
        title = buildString {
            append(baseTitle)
            if (freedNote > 0L) {
                append(" • ")
                append(
                    getString(
                        R.string.recordings_auto_freed,
                        RecordingManager.formatBytes(freedNote)
                    )
                )
            }
            if (deleteBlocked) {
                append(" • ")
                append(getString(R.string.recordings_auto_delete_blocked))
            }
        }
        rowsAdapter.clear()
        // 8.3.0 (Juniper): sort & filter controls, always on top
        // so the list order can change without leaving the screen.
        // 9.1.1: the high-contrast focus setting reaches these rails too.
        val highContrast = AppSettings.highContrastFocus(requireContext())
        val sort = AppSettings.recordingsSort(requireContext())
        val filter = AppSettings.recordingsFilter(requireContext())
        val controlsAdapter = ArrayObjectAdapter(ActionPresenter(highContrast))
        controlsAdapter.add(
            MenuAction("sort", getString(R.string.recordings_sort), sortLabel(sort))
        )
        controlsAdapter.add(
            MenuAction("filter", getString(R.string.recordings_filter), filterLabel(filter))
        )
        rowsAdapter.add(
            ListRow(HeaderItem(getString(R.string.recordings_sort_filter)), controlsAdapter)
        )
        if (scheduled.isNotEmpty()) {
            val scheduledAdapter = ArrayObjectAdapter(ScheduledRecordingPresenter())
            scheduled.forEach { scheduledAdapter.add(it) }
            rowsAdapter.add(
                ListRow(HeaderItem(getString(R.string.scheduled_recordings)), scheduledAdapter)
            )
        }
        // 9.0.0 (Nova): Continue watching — recordings whose
        // last viewing stopped inside the resume window, most
        // recently watched first (the store's own order).
        // Joining from the progress side means a deleted
        // recording's leftover entry simply finds no recording
        // and drops out of the row on its own.
        val byId = recordings.associateBy { it.id }
        val continueEntries = PlaylistRepository.recordingProgress(requireContext())
            .filter { it.isResumable() }
            .mapNotNull { progress ->
                byId[progress.recordingId]?.let { ContinueWatchingEntry(it, progress) }
            }
        if (continueEntries.isNotEmpty()) {
            val continueAdapter = ArrayObjectAdapter(ContinueWatchingPresenter())
            continueEntries.forEach { continueAdapter.add(it) }
            rowsAdapter.add(
                ListRow(HeaderItem(getString(R.string.continue_watching)), continueAdapter)
            )
        }
        val shown = RecordingLibrary.sortAndFilter(recordings, sort, filter)
        if (shown.isEmpty()) {
            if (recordings.isEmpty() && scheduled.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_recordings, Toast.LENGTH_LONG).show()
            }
            return
        }
        val adapter = ArrayObjectAdapter(RecordingPresenter(highContrast))
        shown.forEach { adapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.recordings)), adapter))
    }

    /** 8.2.0 (Nova): a scheduled recording's options — its details
     * and the one action that makes sense before it starts: cancel
     * it (store entry + start alarm both go). */
    private fun showScheduledRecordingOptions(
        scheduled: PlaylistRepository.ScheduledRecording
    ) {
        val whenText = GuideData.formatDate(scheduled.startMillis) + ", " +
            GuideData.formatTimeRange(scheduled.startMillis, scheduled.stopMillis)
        AlertDialog.Builder(requireContext())
            .setTitle(scheduled.title)
            .setMessage(
                getString(
                    R.string.scheduled_recording_info,
                    scheduled.title,
                    scheduled.channelName,
                    whenText
                )
            )
            .setPositiveButton(R.string.scheduled_recording_cancel_action) { _, _ ->
                PlaylistRepository.removeScheduledRecording(
                    requireContext(), scheduled.channelId, scheduled.startMillis
                )
                RecordingManager.cancelRecordingStart(requireContext(), scheduled)
                Toast.makeText(
                    requireContext(),
                    R.string.scheduled_recording_cancelled_toast,
                    Toast.LENGTH_SHORT
                ).show()
                loadRecordings()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 8.3.0 (Juniper): tapping a control cycles it to the next
     * value, saves the choice, and reloads the list. */
    private fun onMenuAction(action: MenuAction) {
        when (action.id) {
            "sort" -> {
                val next = when (AppSettings.recordingsSort(requireContext())) {
                    RecordingLibrary.Sort.NEWEST -> RecordingLibrary.Sort.OLDEST
                    RecordingLibrary.Sort.OLDEST -> RecordingLibrary.Sort.LARGEST
                    RecordingLibrary.Sort.LARGEST -> RecordingLibrary.Sort.SMALLEST
                    RecordingLibrary.Sort.SMALLEST -> RecordingLibrary.Sort.NAME
                    RecordingLibrary.Sort.NAME -> RecordingLibrary.Sort.NEWEST
                }
                AppSettings.setRecordingsSort(requireContext(), next)
                loadRecordings()
            }
            "filter" -> {
                val next = when (AppSettings.recordingsFilter(requireContext())) {
                    RecordingLibrary.Filter.ALL -> RecordingLibrary.Filter.IN_PROGRESS
                    RecordingLibrary.Filter.IN_PROGRESS -> RecordingLibrary.Filter.FINISHED
                    RecordingLibrary.Filter.FINISHED -> RecordingLibrary.Filter.ALL
                }
                AppSettings.setRecordingsFilter(requireContext(), next)
                loadRecordings()
            }
        }
    }

    private fun sortLabel(sort: RecordingLibrary.Sort): String = getString(
        when (sort) {
            RecordingLibrary.Sort.NEWEST -> R.string.sort_newest
            RecordingLibrary.Sort.OLDEST -> R.string.sort_oldest
            RecordingLibrary.Sort.LARGEST -> R.string.sort_largest
            RecordingLibrary.Sort.SMALLEST -> R.string.sort_smallest
            RecordingLibrary.Sort.NAME -> R.string.sort_name
        }
    )

    private fun filterLabel(filter: RecordingLibrary.Filter): String = getString(
        when (filter) {
            RecordingLibrary.Filter.ALL -> R.string.filter_all
            RecordingLibrary.Filter.IN_PROGRESS -> R.string.filter_in_progress
            RecordingLibrary.Filter.FINISHED -> R.string.filter_finished
        }
    )

    private fun showRecordingOptions(recording: Recording) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(recording.startedAtMillis)
        val info = getString(
            R.string.recording_info,
            recording.channelName,
            date,
            RecordingManager.formatBytes(recording.bytesDownloaded),
            if (recording.inProgress) getString(R.string.recording_in_progress_label)
            else getString(R.string.recording_finished_label)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(recording.channelName)
            .setMessage(info)
            .setPositiveButton(R.string.play) { _, _ -> playRecording(recording) }
            .setNeutralButton(R.string.upload) { _, _ -> uploadRecording(recording) }
            .setNegativeButton(R.string.delete) { _, _ -> deleteRecording(recording) }
            .show()
    }

    private fun uploadRecording(recording: Recording) {
        if (PlaylistRepository.uploadUrl(requireContext()).isBlank()) {
            Toast.makeText(
                requireContext(),
                R.string.upload_url_missing,
                Toast.LENGTH_LONG
            ).show()
            startActivity(Intent(requireContext(), SettingsActivity::class.java))
            return
        }
        if (recording.inProgress) {
            Toast.makeText(
                requireContext(),
                R.string.upload_wait_recording,
                Toast.LENGTH_LONG
            ).show()
            return
        }
        Toast.makeText(requireContext(), R.string.upload_started, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val result = RecordingUploader.upload(requireContext(), recording)
            result.onSuccess { destUrl ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.upload_done, destUrl),
                    Toast.LENGTH_LONG
                ).show()
            }.onFailure { e ->
                Toast.makeText(
                    requireContext(),
                    getString(R.string.upload_failed, e.message ?: "unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun playRecording(recording: Recording) {
        val intent = Intent(requireContext(), PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_RECORDING_ID, recording.id)
        intent.putExtra(PlaybackActivity.EXTRA_RECORDING_URI, recording.uri)
        intent.putExtra(PlaybackActivity.EXTRA_RECORDING_NAME, recording.channelName)
        startActivity(intent)
    }

    private fun deleteRecording(recording: Recording) {
        RecordingManager.deleteRecording(requireContext(), recording.id)
        // 9.0.0 (Nova): a deleted recording's resume point goes
        // with it — the Continue watching row must never point
        // at a recording that no longer exists.
        PlaylistRepository.clearRecordingProgress(requireContext(), recording.id)
        Toast.makeText(requireContext(), R.string.recording_deleted, Toast.LENGTH_SHORT).show()
        loadRecordings()
    }
}
