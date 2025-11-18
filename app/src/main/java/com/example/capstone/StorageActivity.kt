package com.example.capstone

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
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
import android.media.MediaMetadataRetriever
import android.location.Geocoder
import java.util.regex.Pattern

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

        loadVideosFromStorage()
        setupRecyclerView()

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

        selectFullVideoTab()
        storageAdapter.updateList(fullVideoList)
        checkEmptyList()
    }

    private fun loadVideosFromStorage() {
        fullVideoList.clear()

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH
        )

        val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
        val selectionArgs = arrayOf("Movies/MyBlackboxVideos/%")
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val query = contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, sortOrder)

        query?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateAdded = cursor.getLong(dateColumn) * 1000L
                val size = cursor.getLong(sizeColumn)
                val duration = cursor.getLong(durationColumn)
                val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                val locationString = getVideoLocation(contentUri)  // 위치 뽑아오기

                fullVideoList.add(
                    VideoItem(
                        videoPath = contentUri.toString(),
                        date = SimpleDateFormat("yyyy/MM/dd", Locale.KOREA).format(Date(dateAdded)),
                        time = SimpleDateFormat("HH시 mm분", Locale.KOREA).format(Date(dateAdded)),
                        location = locationString,
                        videoTime = formatDuration(duration),
                        videoSize = formatFileSize(size)
                    )
                )
            }
        }
    }

    // [핵심 함수 1] 비디오 파일 내부에서 위치 정보를 추출하고 -> 주소로 변환까지 수행
    private fun getVideoLocation(videoUri: Uri): String {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(this, videoUri)
            // 영상 메타데이터 중 '위치' 키값을 가져옵니다. (형식 예: "+37.5665+126.9780/")
            val locationMetadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)

            if (locationMetadata != null) {
                // 이상한 기호(+/-)로 되어있는 문자열을 파싱해서 숫자(위도, 경도)로 바꿉니다.
                val (lat, lon) = parseLocationString(locationMetadata)
                // 숫자를 한글 주소로 바꿉니다.
                return getAddressFromLocation(lat, lon)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // 리소스 해제 (매우 중요)
            retriever.release()
        }
        return "위치 정보 없음"
    }

    // [핵심 함수 2] "+37.5665+126.9780/" 같은 문자열을 위도, 경도 숫자로 분리
    private fun parseLocationString(location: String): Pair<Double, Double> {
        // ISO-6709 형식 파싱을 위한 정규식
        val pattern = Pattern.compile("([+-]\\d+\\.\\d+)([+-]\\d+\\.\\d+)")
        val matcher = pattern.matcher(location)

        if (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull() ?: 0.0
            val lon = matcher.group(2)?.toDoubleOrNull() ?: 0.0
            return Pair(lat, lon)
        }
        return Pair(0.0, 0.0)
    }

    // [핵심 함수 3] 위도/경도 숫자를 한글 주소로 변환 (지난번 코드와 동일)
    private fun getAddressFromLocation(lat: Double, lon: Double): String {
        if (lat == 0.0 && lon == 0.0) return "위치 정보 없음"
        return try {
            val geocoder = Geocoder(this, Locale.KOREA)
            val addresses = geocoder.getFromLocation(lat, lon, 1)
            if (!addresses.isNullOrEmpty()) addresses[0].getAddressLine(0) else "알 수 없는 위치"
        } catch (e: Exception) {
            "위치 변환 실패"
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
                    val deletedRows = contentResolver.delete(Uri.parse(deletedItem.videoPath), null, null)
                    if (deletedRows > 0) {
                        Toast.makeText(this, "영상을 삭제했습니다.", Toast.LENGTH_SHORT).show()
                        fullVideoList.remove(deletedItem)
                        eventVideoList.remove(deletedItem)
                        storageAdapter.updateList(if (binding.fullVideoUnderline.visibility == View.VISIBLE) fullVideoList else eventVideoList)
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