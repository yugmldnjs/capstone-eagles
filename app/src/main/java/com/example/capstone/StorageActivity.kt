package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.capstone.databinding.ActivityStorageBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.log10
import kotlin.math.pow
import androidx.core.net.toUri
import androidx.core.view.isVisible
import kotlin.collections.mutableListOf
import android.media.MediaMetadataRetriever
import androidx.lifecycle.lifecycleScope
import com.example.capstone.util.LocationUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VideoItem(
    val videoPath: String,
    val date: String,
    val time: String,
    val location: String,
    val videoTime: String,
    val videoSize: String
)

class StorageActivity : AppCompatActivity() {

    private lateinit var binding: ActivityStorageBinding
    private lateinit var storageAdapter: StorageAdapter

    private val fullVideoList = mutableListOf<VideoItem>()
    private val eventVideoList = mutableListOf<VideoItem>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityStorageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }
        setupRecyclerView()
        lifecycleScope.launch {
            // suspend 함수들을 순차적으로 호출
            loadVideosFromStorage(fullVideoList, "recordings")
            loadVideosFromStorage(eventVideoList, "Events")

            // 모든 로딩이 끝나면 메인 스레드에서 UI 업데이트
            withContext(Dispatchers.Main) {
                selectFullVideoTab()
                storageAdapter.updateList(fullVideoList)
                checkEmptyList()
            }
        }

        binding.fullVideoBtn.setOnClickListener {
            selectFullVideoTab()
            storageAdapter.updateList(fullVideoList)
            checkEmptyList()
        }

        binding.eventVideoBtn.setOnClickListener {
            selectEventVideoTab()
            storageAdapter.updateList(eventVideoList)
            checkEmptyList()
        }
    }

    private suspend fun loadVideosFromStorage(videoList: MutableList<VideoItem>, dir: String) {
        videoList.clear()
        Log.d("StorageActivity", "dir: $dir==============================")

        // 1. 폴더에서 영상 불러오기
        val videoDir = getExternalFilesDir(dir)
        if (videoDir != null && videoDir.exists()) {
            val videoFiles =
                videoDir.listFiles { file -> file.isFile && file.extension == "mp4" }
            videoFiles?.sortByDescending { it.lastModified() } // 최신 순으로 정렬

            videoFiles?.forEach { file ->
                val videoItem = createVideoItemFromFile(file)
                if (videoItem != null) {
                    videoList.add(videoItem)
                }
            }
        } else {
            Log.w("StorageActivity", "${dir} 디렉토리가 없거나 접근할 수 없습니다.")
        }
    }

    // 파일을 기반으로 VideoItem 객체를 생성하는 헬퍼 함수
    private suspend fun createVideoItemFromFile(file: File): VideoItem? {
        try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)

            val date = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).parse(file.nameWithoutExtension.substring(file.nameWithoutExtension.length-15))
            val size = file.length()
            val durationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            val duration = durationString?.toLongOrNull() ?: 0L

            retriever.release() // 리소스 해제

            val locationString = LocationUtils.getAddressFromFile(this, file.absolutePath)

            return VideoItem(
                videoPath = file.absolutePath,
                date = SimpleDateFormat("yyyy/MM/dd", Locale.KOREA).format(date),
                time = SimpleDateFormat("HH시 mm분", Locale.KOREA).format(date),
                location = locationString,
                videoTime = formatDuration(duration),
                videoSize = formatFileSize(size)
            )
        } catch (e: Exception) {
            Log.e("StorageActivity", "파일 메타데이터를 읽는 중 오류 발생: ${file.name}", e)
            return null
        }
    }

    private fun formatDuration(millis: Long): String {
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis)
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) - TimeUnit.MINUTES.toSeconds(minutes)
        return if (minutes > 0) String.format(Locale.getDefault(), "%d분 %d초", minutes, seconds)
        else String.format(Locale.getDefault(), "%d초", seconds)
    }

    private fun formatFileSize(size: Long): String {
        if (size <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(size.toDouble()) / log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", size / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun setupRecyclerView() {
        storageAdapter = StorageAdapter(
            mutableListOf(),
            onDeleteCallback = { deletedItem ->
                try {
                    val deletedRows = contentResolver.delete(deletedItem.videoPath.toUri(), null, null)
                    if (deletedRows > 0) {
                        Toast.makeText(this, "영상을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                        fullVideoList.remove(deletedItem)
                        eventVideoList.remove(deletedItem)
                        storageAdapter.updateList(if (binding.fullVideoUnderline.isVisible) fullVideoList else eventVideoList)
                        checkEmptyList()
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "영상 삭제에 실패했습니다.", Toast.LENGTH_SHORT).show()
                    Log.e("StorageActivity", "Error deleting video", e)
                }
            },
            onItemClick = { clickedVideo ->
                val intent = Intent(this, VideoActivity::class.java).apply {
                    putExtra("VIDEO_PATH", clickedVideo.videoPath)
                }
                startActivity(intent)
            }
        )

        binding.storageRecyclerView.adapter = storageAdapter
        binding.storageRecyclerView.layoutManager = LinearLayoutManager(this)
        binding.storageRecyclerView.addItemDecoration(DividerItemDecoration(this, LinearLayoutManager.VERTICAL))
    }

    private fun selectFullVideoTab() {
        binding.fullVideoUnderline.visibility = View.VISIBLE
        binding.eventVideoUnderline.visibility = View.GONE
    }

    private fun selectEventVideoTab() {
        binding.fullVideoUnderline.visibility = View.GONE
        binding.eventVideoUnderline.visibility = View.VISIBLE
    }

    private fun checkEmptyList() {
        binding.emptyTextView.visibility = if (storageAdapter.itemCount == 0) View.VISIBLE else View.GONE
        binding.storageRecyclerView.visibility = if (storageAdapter.itemCount == 0) View.GONE else View.VISIBLE
    }
}