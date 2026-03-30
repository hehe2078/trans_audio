package com.example.mp4tomp3

import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SubtitleRecognitionController(
    private val activity: AppCompatActivity,
    private val rootView: View,
    private val subtitleRecognizer: SubtitleRecognizer,
    private val onChooseSource: () -> Unit
) {
    private val chooseButton: Button = rootView.findViewById(R.id.subtitleChooseButton)
    private val recognizeButton: Button = rootView.findViewById(R.id.subtitleRecognizeButton)
    private val selectedFileText: TextView = rootView.findViewById(R.id.subtitleSelectedFileText)
    private val sourceInfoText: TextView = rootView.findViewById(R.id.subtitleSourceInfoText)
    private val statusText: TextView = rootView.findViewById(R.id.subtitleStatusText)
    private val resultText: TextView = rootView.findViewById(R.id.subtitleResultText)
    private val progressBar: ProgressBar = rootView.findViewById(R.id.subtitleProgressBar)

    private var selectedSourceUri: Uri? = null
    private var selectedSourceInfo: MediaSourceInfo? = null
    private var recognitionJob: Job? = null

    init {
        chooseButton.setOnClickListener { onChooseSource() }
        recognizeButton.setOnClickListener { startRecognition() }
        resetUi()
    }

    fun bindSelectedSource(uri: Uri) {
        selectedSourceUri = uri
        selectedSourceInfo = MediaSourceUtils.readSourceInfo(activity, uri)
        selectedFileText.text = activity.getString(
            R.string.selected_file_value,
            MediaSourceUtils.queryDisplayName(activity, uri) ?: uri.toString()
        )
        sourceInfoText.text = activity.getString(
            R.string.source_info_value,
            selectedSourceInfo?.describe(activity) ?: activity.getString(R.string.error_unknown_stream)
        )
        statusText.text = activity.getString(R.string.subtitle_status_ready)
        resultText.text = activity.getString(R.string.subtitle_result_placeholder)
        recognizeButton.isEnabled = true
    }

    fun release() {
        recognitionJob?.cancel()
        activity.lifecycleScope.launch {
            subtitleRecognizer.release()
        }
    }

    private fun startRecognition() {
        val uri = selectedSourceUri ?: run {
            statusText.text = activity.getString(R.string.status_choose_file_first)
            return
        }

        val sourceInfo = selectedSourceInfo ?: MediaSourceUtils.readSourceInfo(activity, uri)
        if (sourceInfo == null || !sourceInfo.hasAudio) {
            statusText.text = activity.getString(R.string.subtitle_error_no_audio)
            resultText.text = activity.getString(R.string.subtitle_result_placeholder)
            return
        }

        if (recognitionJob?.isActive == true) {
            return
        }

        recognitionJob = activity.lifecycleScope.launch {
            setLoading(true)
            statusText.text = activity.getString(R.string.subtitle_status_processing)
            resultText.text = activity.getString(R.string.subtitle_result_placeholder)

            try {
                val result = subtitleRecognizer.recognize(uri)
                statusText.text = activity.getString(
                    R.string.subtitle_status_success,
                    result.languageHint
                )
                resultText.text = result.text
            } catch (e: Exception) {
                statusText.text = activity.getString(
                    R.string.subtitle_status_failed,
                    e.message ?: activity.getString(R.string.status_failed)
                )
                resultText.text = activity.getString(R.string.subtitle_result_placeholder)
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        chooseButton.isEnabled = !isLoading
        recognizeButton.isEnabled = !isLoading && selectedSourceUri != null
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun resetUi() {
        selectedFileText.text = activity.getString(R.string.selected_file_empty)
        sourceInfoText.text = activity.getString(R.string.source_info_empty)
        statusText.text = activity.getString(R.string.subtitle_status_idle)
        resultText.text = activity.getString(R.string.subtitle_result_placeholder)
        recognizeButton.isEnabled = false
        progressBar.visibility = View.GONE
    }
}
