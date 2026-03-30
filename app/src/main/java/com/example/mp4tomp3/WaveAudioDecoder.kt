package com.example.mp4tomp3

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

fun decodeWaveFile(file: File): FloatArray {
    val byteStream = ByteArrayOutputStream()
    file.inputStream().use { it.copyTo(byteStream) }
    val bytes = byteStream.toByteArray()
    require(bytes.size > 44) { "Invalid wav file" }

    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    val channels = buffer.getShort(22).toInt().coerceAtLeast(1)
    buffer.position(44)
    val shortBuffer = buffer.asShortBuffer()
    val shortArray = ShortArray(shortBuffer.limit())
    shortBuffer.get(shortArray)

    return FloatArray(shortArray.size / channels) { index ->
        when (channels) {
            1 -> (shortArray[index] / 32767.0f).coerceIn(-1f, 1f)
            else -> {
                val left = shortArray[channels * index]
                val right = shortArray[channels * index + 1]
                ((left + right) / 32767.0f / 2.0f).coerceIn(-1f, 1f)
            }
        }
    }
}
