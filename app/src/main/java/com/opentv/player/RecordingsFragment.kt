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
                is PlaylistRepository.ScheduledRecording ->
                    showScheduledRecordingOptions(item)
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
        val recordings = RecordingManager.listRecordings(requireContext())
        // 8.2.0 (Nova): scheduled recordings get their own row at
        // the top — they are not downloads yet, so they don't count
        // toward the title's size line, but they belong on this
        // screen: it's where a user looks to manage them.
        val scheduled = PlaylistRepository.scheduledRecordings(requireContext())
        // Total bytes from the list already fetched — the old code
        // called totalBytesUsed, which re-ran the whole DownloadIndex
        // query just to sum the same rows.
        val used = recordings.sumOf { it.bytesDownloaded }
        title = if (recordings.isEmpty()) {
            getString(R.string.recordings)
        } else {
            getString(
                R.string.recordings_with_size,
                recordings.size,
                RecordingManager.formatBytes(used)
            )
        }
        rowsAdapter.clear()
        if (scheduled.isNotEmpty()) {
            val scheduledAdapter = ArrayObjectAdapter(ScheduledRecordingPresenter())
            scheduled.forEach { scheduledAdapter.add(it) }
            rowsAdapter.add(
                ListRow(HeaderItem(getString(R.string.scheduled_recordings)), scheduledAdapter)
            )
        }
        if (recordings.isEmpty()) {
            if (scheduled.isEmpty()) {
                Toast.makeText(requireContext(), R.string.no_recordings, Toast.LENGTH_LONG).show()
            }
            return
        }
        val adapter = ArrayObjectAdapter(RecordingPresenter())
        recordings.forEach { adapter.add(it) }
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
        Toast.makeText(requireContext(), R.string.recording_deleted, Toast.LENGTH_SHORT).show()
        loadRecordings()
    }
}
