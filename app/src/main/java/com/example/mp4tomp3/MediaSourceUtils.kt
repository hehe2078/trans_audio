package com.example.mp4tomp3

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import java.io.FileInputStream
import java.io.InputStream

data class MediaSourceInfo(
    val hasAudio: Boolean,
    val hasVideo: Boolean
) {
    fun describe(context: Context): String {
        return when {
            hasAudio && hasVideo -> context.getString(R.string.media_info_audio_video)
            hasVideo -> context.getString(R.string.media_info_video_only)
            hasAudio -> context.getString(R.string.media_info_audio_only)
            else -> context.getString(R.string.error_unknown_stream)
        }
    }
}

object MediaSourceUtils {
    fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }

        return uri.lastPathSegment?.substringAfterLast('/')
    }

    fun readSourceInfo(context: Context, uri: Uri): MediaSourceInfo? {
        return runCatching {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(context, uri)
                val hasVideo =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasAudio =
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
                MediaSourceInfo(hasAudio = hasAudio, hasVideo = hasVideo)
            }
        }.getOrNull()
    }

    fun openInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            FileInputStream(path)
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }
}
