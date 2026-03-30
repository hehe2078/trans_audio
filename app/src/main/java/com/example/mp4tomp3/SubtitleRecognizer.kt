package com.example.mp4tomp3

import android.content.Context
import android.net.Uri
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.whispercpp.whisper.WhisperContext
import java.io.File
import java.io.FileOutputStream

data class RecognitionResult(
    val text: String,
    val sourceName: String,
    val languageHint: String
)

class SubtitleRecognizer(
    private val context: Context
) {
    private var whisperContext: WhisperContext? = null

    suspend fun recognize(sourceUri: Uri): RecognitionResult {
        val sourceName = MediaSourceUtils.queryDisplayName(context, sourceUri)
            ?: "media_${System.currentTimeMillis()}"
        val tempInput = File(context.cacheDir, "subtitle_input_${System.currentTimeMillis()}_$sourceName")
        val tempOutput = File(context.cacheDir, "subtitle_audio_${System.currentTimeMillis()}.wav")

        try {
            MediaSourceUtils.openInputStream(context, sourceUri)?.use { input ->
                FileOutputStream(tempInput).use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException(context.getString(R.string.error_open_source))

            convertSourceToWave(tempInput, tempOutput)
            val audioData = decodeWaveFile(tempOutput)
            if (audioData.isEmpty()) {
                throw IllegalStateException(context.getString(R.string.subtitle_error_empty_audio))
            }

            val transcription = getOrCreateContext()
                .transcribeData(audioData, language = "auto", printTimestamp = false)
                .trim()

            if (transcription.isBlank()) {
                throw IllegalStateException(context.getString(R.string.subtitle_error_empty_result))
            }

            return RecognitionResult(
                text = transcription,
                sourceName = sourceName,
                languageHint = context.getString(R.string.subtitle_language_auto)
            )
        } finally {
            tempInput.delete()
            tempOutput.delete()
        }
    }

    suspend fun release() {
        whisperContext?.release()
        whisperContext = null
    }

    private fun convertSourceToWave(tempInput: File, tempOutput: File) {
        val quotedInput = quotePath(tempInput.absolutePath)
        val quotedOutput = quotePath(tempOutput.absolutePath)
        val command = "-y -i $quotedInput -vn -ac 1 -ar 16000 -c:a pcm_s16le $quotedOutput"
        val session = FFmpegKit.execute(command)
        if (!ReturnCode.isSuccess(session.returnCode) || !tempOutput.exists() || tempOutput.length() == 0L) {
            val details = session.allLogsAsString.takeIf { !it.isNullOrBlank() }
            throw IllegalStateException(details ?: context.getString(R.string.subtitle_error_wav_failed))
        }
    }

    private fun getOrCreateContext(): WhisperContext {
        return whisperContext ?: WhisperContext.createContextFromAsset(
            context.assets,
            MODEL_ASSET_PATH
        ).also { whisperContext = it }
    }

    private fun quotePath(path: String): String {
        val escaped = path.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    companion object {
        private const val MODEL_ASSET_PATH = "models/ggml-tiny.bin"
    }
}
