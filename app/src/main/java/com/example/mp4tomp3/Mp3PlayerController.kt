package com.example.mp4tomp3

import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class Mp3PlayerController(
    private val activity: AppCompatActivity,
    private val rootView: View
) {
    private val listView: ListView = rootView.findViewById(R.id.mp3ListView)
    private val emptyText: TextView = rootView.findViewById(R.id.emptyText)
    private val nowPlayingText: TextView = rootView.findViewById(R.id.nowPlayingText)
    private val refreshButton: Button = rootView.findViewById(R.id.refreshButton)
    private val playPauseButton: Button = rootView.findViewById(R.id.playPauseButton)
    private val stopButton: Button = rootView.findViewById(R.id.stopButton)
    private val seekBar: SeekBar = rootView.findViewById(R.id.playbackSeekBar)
    private val currentTimeText: TextView = rootView.findViewById(R.id.currentTimeText)
    private val durationText: TextView = rootView.findViewById(R.id.durationText)

    private val mp3Items = mutableListOf<Mp3Item>()
    private val adapter = ArrayAdapter(activity, android.R.layout.simple_list_item_1, mutableListOf<String>())
    private var mediaPlayer: MediaPlayer? = null
    private var isUserSeeking = false
    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer ?: return
            if (!isUserSeeking) {
                val position = player.currentPosition
                val duration = player.duration.coerceAtLeast(0)
                seekBar.max = duration
                seekBar.progress = position
                currentTimeText.text = formatDuration(position)
                durationText.text = formatDuration(duration)
            }
            progressHandler.postDelayed(this, 300)
        }
    }

    init {
        bindUi()
        resetPlaybackUi()
        loadMp3List()
    }

    fun refresh() {
        loadMp3List()
    }

    fun release() {
        progressHandler.removeCallbacks(progressUpdater)
        releasePlayer()
    }

    private fun bindUi() {
        listView.adapter = adapter

        refreshButton.setOnClickListener { loadMp3List() }
        playPauseButton.setOnClickListener { togglePlayPause() }
        stopButton.setOnClickListener { stopPlayback() }
        listView.setOnItemClickListener { _, _, position, _ ->
            playItem(mp3Items[position])
        }
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    currentTimeText.text = formatDuration(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val player = mediaPlayer
                if (player != null && seekBar != null) {
                    player.seekTo(seekBar.progress)
                    currentTimeText.text = formatDuration(seekBar.progress)
                }
                isUserSeeking = false
            }
        })
    }

    private fun loadMp3List() {
        val items = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryMp3sFromMediaStore()
        } else {
            queryMp3sFromFilesystem()
        }

        mp3Items.clear()
        mp3Items.addAll(items.sortedByDescending { it.dateAdded })
        adapter.clear()
        adapter.addAll(mp3Items.map { it.displayName })
        adapter.notifyDataSetChanged()

        emptyText.text = if (mp3Items.isEmpty()) {
            activity.getString(R.string.player_empty)
        } else {
            activity.getString(R.string.player_hint)
        }
    }

    private fun playItem(item: Mp3Item) {
        try {
            releasePlayer()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(activity, item.uri)
                setOnPreparedListener { player ->
                    seekBar.max = player.duration.coerceAtLeast(0)
                    seekBar.progress = 0
                    currentTimeText.text = formatDuration(0)
                    durationText.text = formatDuration(player.duration)
                    nowPlayingText.text = activity.getString(R.string.player_now_playing, item.displayName)
                    playPauseButton.text = activity.getString(R.string.player_pause)
                    playPauseButton.isEnabled = true
                    stopButton.isEnabled = true
                    player.start()
                    progressHandler.removeCallbacks(progressUpdater)
                    progressHandler.post(progressUpdater)
                }
                setOnCompletionListener {
                    resetPlaybackUi()
                    releasePlayer()
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            resetPlaybackUi()
            releasePlayer()
            Toast.makeText(
                activity,
                activity.getString(
                    R.string.player_play_failed,
                    e.message ?: activity.getString(R.string.status_failed)
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopPlayback() {
        progressHandler.removeCallbacks(progressUpdater)
        releasePlayer()
        resetPlaybackUi()
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressUpdater)
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
    }

    private fun togglePlayPause() {
        val player = mediaPlayer ?: return
        if (player.isPlaying) {
            player.pause()
            playPauseButton.text = activity.getString(R.string.player_resume)
        } else {
            player.start()
            playPauseButton.text = activity.getString(R.string.player_pause)
            progressHandler.removeCallbacks(progressUpdater)
            progressHandler.post(progressUpdater)
        }
    }

    private fun resetPlaybackUi() {
        nowPlayingText.text = activity.getString(R.string.player_idle)
        currentTimeText.text = formatDuration(0)
        durationText.text = formatDuration(0)
        seekBar.max = 0
        seekBar.progress = 0
        playPauseButton.text = activity.getString(R.string.player_play)
        playPauseButton.isEnabled = false
        stopButton.isEnabled = false
    }

    private fun formatDuration(milliseconds: Int): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.US, "%02d:%02d", minutes, seconds)
    }

    private fun queryMp3sFromMediaStore(): List<Mp3Item> {
        val items = mutableListOf<Mp3Item>()
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DISPLAY_NAME,
            MediaStore.Downloads.DATE_ADDED
        )
        val selection =
            "${MediaStore.Downloads.MIME_TYPE}=? AND ${MediaStore.Downloads.RELATIVE_PATH}=?"
        val selectionArgs = arrayOf("audio/mpeg", "${StorageConfig.RELATIVE_DOWNLOAD_PATH}/")

        activity.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Downloads.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME)
            val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idIndex)
                val uri = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id.toString())
                items.add(
                    Mp3Item(
                        displayName = cursor.getString(nameIndex),
                        uri = uri,
                        dateAdded = cursor.getLong(dateIndex)
                    )
                )
            }
        }
        return items
    }

    private fun queryMp3sFromFilesystem(): List<Mp3Item> {
        val downloadsDir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                ?: return emptyList()
        val targetDir = File(downloadsDir, StorageConfig.DOWNLOAD_SUBDIRECTORY)
        if (!targetDir.exists() || !targetDir.isDirectory) {
            return emptyList()
        }

        return targetDir.listFiles()
            ?.filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            ?.map { file ->
                Mp3Item(
                    displayName = file.name,
                    uri = Uri.fromFile(file),
                    dateAdded = file.lastModified()
                )
            }
            ?: emptyList()
    }
}

private data class Mp3Item(
    val displayName: String,
    val uri: Uri,
    val dateAdded: Long
)
