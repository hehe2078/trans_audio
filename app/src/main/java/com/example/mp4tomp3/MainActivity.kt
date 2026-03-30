package com.example.mp4tomp3

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : AppCompatActivity() {

    private lateinit var chooseButton: Button
    private lateinit var convertButton: Button
    private lateinit var openPlayerButton: Button
    private lateinit var statusText: TextView
    private lateinit var selectedFileText: TextView
    private lateinit var sourceInfoText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var bitrateSpinner: Spinner
    private lateinit var bitrateLabel: TextView

    private lateinit var selectedBitrate: AudioBitrate

    private var selectedSourceUri: Uri? = null
    private var pendingPermissionUri: Uri? = null
    private var conversionJob: Job? = null
    private var selectedSourceInfo: SourceInfo? = null

    private val pickMediaLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                updateStatus(getString(R.string.status_pick_cancelled))
                return@registerForActivityResult
            }

            grantPersistableReadPermission(uri)
            bindSelectedSource(uri, fromExternalApp = false)
        }

    private val requestLegacyWritePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val pendingUri = pendingPermissionUri
            pendingPermissionUri = null

            if (granted && pendingUri != null) {
                startConversion(pendingUri)
            } else {
                updateStatus(getString(R.string.status_permission_denied))
                toast(getString(R.string.status_permission_denied))
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        chooseButton = findViewById(R.id.chooseButton)
        convertButton = findViewById(R.id.convertButton)
        openPlayerButton = findViewById(R.id.openPlayerButton)
        statusText = findViewById(R.id.statusText)
        selectedFileText = findViewById(R.id.selectedFileText)
        sourceInfoText = findViewById(R.id.sourceInfoText)
        progressBar = findViewById(R.id.progressBar)
        bitrateSpinner = findViewById(R.id.bitrateSpinner)
        bitrateLabel = findViewById(R.id.bitrateLabel)

        bindBitrateSpinner()
        handleIncomingIntent(intent)

        chooseButton.setOnClickListener {
            pickMediaLauncher.launch(arrayOf("video/*", "audio/*"))
        }

        openPlayerButton.setOnClickListener {
            startActivity(Intent(this, PlayerActivity::class.java))
        }

        convertButton.setOnClickListener {
            val uri = selectedSourceUri
            if (uri == null) {
                toast(getString(R.string.status_choose_file_first))
                return@setOnClickListener
            }

            val sourceInfo = selectedSourceInfo ?: readSourceInfo(uri)
            if (sourceInfo == null || !sourceInfo.hasAudio) {
                updateStatus(
                    getString(
                        R.string.status_failed_with_reason,
                        getString(R.string.error_audio_required)
                    )
                )
                toast(getString(R.string.error_audio_required))
                return@setOnClickListener
            }

            if (needsLegacyWritePermission() && !hasLegacyWritePermission()) {
                pendingPermissionUri = uri
                requestLegacyWritePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            } else {
                startConversion(uri)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun startConversion(sourceUri: Uri) {
        if (conversionJob?.isActive == true) {
            return
        }

        conversionJob = lifecycleScope.launch {
            setLoading(true)
            updateStatus(getString(R.string.status_processing))

            try {
                val result = withContext(Dispatchers.IO) {
                    convertSourceToMp3(sourceUri)
                }
                val successMessage = getString(
                    R.string.status_success,
                    result.outputName,
                    result.destinationDescription
                )
                updateStatus(successMessage)
                toast(successMessage)
            } catch (e: Exception) {
                val errorMessage = e.message ?: getString(R.string.status_failed)
                updateStatus(getString(R.string.status_failed_with_reason, errorMessage))
                toast(getString(R.string.status_failed))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun convertSourceToMp3(sourceUri: Uri): ConversionResult {
        val sourceName = queryDisplayName(sourceUri) ?: "media_${System.currentTimeMillis()}"
        val baseName = sourceName.substringBeforeLast('.').ifBlank { "output_${System.currentTimeMillis()}" }
        val tempInput = File(cacheDir, "input_${System.currentTimeMillis()}_${sourceName}")
        val tempOutput = File(cacheDir, "output_${System.currentTimeMillis()}.mp3")

        try {
            openSourceInputStream(sourceUri)?.use { input ->
                FileOutputStream(tempInput).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException(getString(R.string.error_open_source))

            runMp3Conversion(tempInput, tempOutput)

            val outputName = buildOutputName(baseName)
            val destination = writeOutputToDownloads(tempOutput, outputName)
            return ConversionResult(outputName, destination)
        } finally {
            tempInput.delete()
            tempOutput.delete()
        }
    }

    private fun runMp3Conversion(tempInput: File, tempOutput: File) {
        val command = buildMp3Command(
            inputPath = tempInput.absolutePath,
            outputPath = tempOutput.absolutePath,
            bitrate = selectedBitrate
        )

        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode) || !tempOutput.exists() || tempOutput.length() == 0L) {
            val details = session.allLogsAsString.takeIf { !it.isNullOrBlank() }
            throw IllegalStateException(details ?: getString(R.string.error_convert_failed))
        }
    }

    private fun buildMp3Command(inputPath: String, outputPath: String, bitrate: AudioBitrate): String {
        val quotedInput = quotePath(inputPath)
        val quotedOutput = quotePath(outputPath)
        return "-y -i $quotedInput -vn -acodec libmp3lame -b:a ${bitrate.ffmpegValue} $quotedOutput"
    }

    private fun writeOutputToDownloads(tempOutput: File, outputName: String): String {
        val mimeType = "audio/mpeg"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${StorageConfig.RELATIVE_DOWNLOAD_PATH}/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException(getString(R.string.error_create_download))

            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(tempOutput).use { input -> input.copyTo(output) }
                } ?: throw IllegalStateException(getString(R.string.error_write_download))

                val finalizeValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                contentResolver.update(uri, finalizeValues, null, null)
                "${StorageConfig.RELATIVE_DOWNLOAD_PATH}/$outputName"
            } catch (e: Exception) {
                contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val downloadsDir =
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    ?: throw IllegalStateException(getString(R.string.error_download_dir))

            if (!downloadsDir.exists() && !downloadsDir.mkdirs()) {
                throw IllegalStateException(getString(R.string.error_download_dir))
            }

            val targetDir = File(downloadsDir, StorageConfig.DOWNLOAD_SUBDIRECTORY)
            if (!targetDir.exists() && !targetDir.mkdirs()) {
                throw IllegalStateException(getString(R.string.error_download_dir))
            }

            val destinationFile = File(targetDir, outputName)
            FileInputStream(tempOutput).use { input ->
                FileOutputStream(destinationFile).use { output -> input.copyTo(output) }
            }

            MediaScannerConnection.scanFile(
                this,
                arrayOf(destinationFile.absolutePath),
                arrayOf(mimeType),
                null
            )
            destinationFile.absolutePath
        }
    }

    private fun buildOutputName(baseName: String): String {
        val safeBaseName = baseName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return "${safeBaseName}_to_mp3.mp3"
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    return cursor.getString(nameIndex)
                }
            }

        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun quotePath(path: String): String {
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    private fun needsLegacyWritePermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    private fun hasLegacyWritePermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun setLoading(isLoading: Boolean) {
        chooseButton.isEnabled = !isLoading
        convertButton.isEnabled = !isLoading && selectedSourceUri != null
        openPlayerButton.isEnabled = !isLoading
        bitrateSpinner.isEnabled = !isLoading
        progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
    }

    private fun updateStatus(message: String) {
        statusText.text = message
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun bindBitrateSpinner() {
        selectedBitrate = AudioBitrate.K192

        bitrateSpinner.adapter = ArrayAdapter.createFromResource(
            this,
            R.array.bitrate_options,
            android.R.layout.simple_spinner_dropdown_item
        )
        bitrateSpinner.setSelection(AudioBitrate.entries.indexOf(selectedBitrate))
        bitrateSpinner.onItemSelectedListener = SimpleItemSelectedListener { position ->
            selectedBitrate = AudioBitrate.entries[position]
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val sourceUri = extractSourceUri(intent) ?: return
        bindSelectedSource(sourceUri, fromExternalApp = true)
    }

    private fun extractSourceUri(intent: Intent?): Uri? {
        if (intent == null) {
            return null
        }

        return when (intent.action) {
            Intent.ACTION_SEND -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
            }

            Intent.ACTION_VIEW -> intent.data
            else -> null
        }
    }

    private fun bindSelectedSource(uri: Uri, fromExternalApp: Boolean) {
        if (fromExternalApp) {
            grantTemporaryReadPermission(uri)
        }

        selectedSourceUri = uri
        selectedSourceInfo = readSourceInfo(uri)
        selectedFileText.text = getString(
            R.string.selected_file_value,
            queryDisplayName(uri) ?: uri.toString()
        )
        sourceInfoText.text = getString(
            R.string.source_info_value,
            selectedSourceInfo?.describe() ?: getString(R.string.error_unknown_stream)
        )
        updateStatus(
            if (fromExternalApp) {
                getString(R.string.status_received_external)
            } else {
                getString(R.string.status_ready)
            }
        )
        convertButton.isEnabled = true
    }

    private fun grantPersistableReadPermission(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some providers do not expose persistable permissions.
        } catch (_: UnsupportedOperationException) {
            // File and some provider URIs do not support persistable access.
        }
    }

    private fun grantTemporaryReadPermission(uri: Uri) {
        try {
            grantPersistableReadPermission(uri)
        } catch (_: Exception) {
            // Best effort only.
        }
    }

    private fun readSourceInfo(uri: Uri): SourceInfo? {
        return runCatching {
            val retriever = MediaMetadataRetriever()
            retriever.use {
                it.setDataSource(this, uri)
                val hasVideo =
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio =
                    it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                SourceInfo(hasAudio = hasAudio, hasVideo = hasVideo)
            }
        }.getOrNull()
    }

    private fun openSourceInputStream(uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(path)
        } else {
            contentResolver.openInputStream(uri)
        }
    }
}

private data class ConversionResult(
    val outputName: String,
    val destinationDescription: String
)

private data class SourceInfo(
    val hasAudio: Boolean,
    val hasVideo: Boolean
) {
    fun describe(): String {
        return when {
            hasAudio && hasVideo -> "包含音频和视频"
            hasVideo -> "仅包含视频"
            hasAudio -> "仅包含音频"
            else -> "未识别到音频或视频流"
        }
    }
}

private enum class AudioBitrate(val ffmpegValue: String) {
    K128("128k"),
    K192("192k"),
    K320("320k")
}

private class SimpleItemSelectedListener(
    private val onSelected: (position: Int) -> Unit
) : AdapterView.OnItemSelectedListener {
    override fun onItemSelected(
        parent: AdapterView<*>?,
        view: View?,
        position: Int,
        id: Long
    ) {
        onSelected(position)
    }

    override fun onNothingSelected(parent: AdapterView<*>?) = Unit
}
