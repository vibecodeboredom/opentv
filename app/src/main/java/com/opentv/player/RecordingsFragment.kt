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
import java.util.Date

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
            if (item is Recording) showRecordingOptions(item)
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
        val used = RecordingManager.totalBytesUsed(requireContext())
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
        if (recordings.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_recordings, Toast.LENGTH_LONG).show()
            return
        }
        val adapter = ArrayObjectAdapter(RecordingPresenter())
        recordings.forEach { adapter.add(it) }
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.recordings)), adapter))
    }

    private fun showRecordingOptions(recording: Recording) {
        val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(recording.startedAtMillis))
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
