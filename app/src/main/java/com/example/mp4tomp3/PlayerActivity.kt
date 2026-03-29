package com.example.mp4tomp3

import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class PlayerActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var nowPlayingText: TextView
    private lateinit var refreshButton: Button
    private lateinit var stopButton: Button

    private val mp3Items = mutableListOf<Mp3Item>()
    private lateinit var adapter: ArrayAdapter<String>
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.player_title)

        listView = findViewById(R.id.mp3ListView)
        emptyText = findViewById(R.id.emptyText)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        refreshButton = findViewById(R.id.refreshButton)
        stopButton = findViewById(R.id.stopButton)

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf())
        listView.adapter = adapter

        refreshButton.setOnClickListener { loadMp3List() }
        stopButton.setOnClickListener { stopPlayback() }
        listView.setOnItemClickListener { _, _, position, _ ->
            playItem(mp3Items[position])
        }

        loadMp3List()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
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
            getString(R.string.player_empty)
        } else {
            getString(R.string.player_hint)
        }
    }

    private fun playItem(item: Mp3Item) {
        try {
            releasePlayer()
            mediaPlayer = MediaPlayer().apply {
                setDataSource(this@PlayerActivity, item.uri)
                setOnCompletionListener {
                    nowPlayingText.text = getString(R.string.player_idle)
                    releasePlayer()
                }
                prepare()
                start()
            }
            nowPlayingText.text = getString(R.string.player_now_playing, item.displayName)
        } catch (e: Exception) {
            nowPlayingText.text = getString(R.string.player_idle)
            releasePlayer()
            Toast.makeText(
                this,
                getString(R.string.player_play_failed, e.message ?: getString(R.string.status_failed)),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun stopPlayback() {
        releasePlayer()
        nowPlayingText.text = getString(R.string.player_idle)
    }

    private fun releasePlayer() {
        mediaPlayer?.runCatching { stop() }
        mediaPlayer?.release()
        mediaPlayer = null
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

        contentResolver.query(
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
