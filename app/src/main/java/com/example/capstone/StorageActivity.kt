package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.capstone.databinding.ActivityStorageBinding
import android.widget.Toast

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

    private val fullVideoList = mutableListOf(
        VideoItem("path1", "2024/09/23", "21시 28분", "광주광역시 북구", "1시간 10분", "15MB"),
        VideoItem("path2", "2024/09/24", "20시 28분", "광주광역시 남구", "2시간 35분", "40MB"),
        VideoItem("path3", "2024/09/25", "23시 28분", "광주광역시 광산구", "1시간 20분", "25MB"),
        VideoItem("path4", "2024/09/26", "10시 15분", "광주광역시 동구", "50분", "12MB"),
        VideoItem("path5", "2024/09/27", "18시 05분", "광주광역시 서구", "1시간 5분", "20MB"),
        VideoItem("path6", "2024/09/23", "21시 28분", "광주광역시 북구", "1시간 10분", "15MB")

    )


    private val eventVideoList = mutableListOf(
        VideoItem("path2", "2024/09/24", "20시 28분", "광주광역시 남구", "3분", "3MB"),
        VideoItem("path3", "2024/09/25", "23시 28분", "광주광역시 광산구", "3분", "3MB")
    )

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
        checkEmptyList()
    }

    private fun setupRecyclerView() {
        storageAdapter = StorageAdapter(
            fullVideoList.toMutableList(),
            onDeleteCallback = { deletedItem -> // 1. 삭제된 아이템을 파라미터로 받습니다.
                // 2. Activity의 원본 리스트에서도 삭제합니다. (영구 삭제)
                fullVideoList.remove(deletedItem)
                eventVideoList.remove(deletedItem)

                // 3. 그 다음, 리스트가 비었는지 확인합니다.
                checkEmptyList()
            },
            onItemClick = { clickedVideo ->
                //Toast.makeText(this, "영상 클릭", Toast.LENGTH_SHORT).show()

                val intent = Intent(this, VideoActivity::class.java)
                intent.putExtra("VIDEO_PATH", clickedVideo.videoPath)
                startActivity(intent)
            }
        )

        // 리사이클러뷰 어댑터~~
        binding.storageRecyclerView.adapter = storageAdapter
        binding.storageRecyclerView.layoutManager = LinearLayoutManager(this)

        val dividerItemDecoration = DividerItemDecoration(this, LinearLayoutManager.VERTICAL)
        binding.storageRecyclerView.addItemDecoration(dividerItemDecoration)
    }


    // 전체 영상 / 사고 영상에 선택된거 밑에 선 긋기
    private fun selectFullVideoTab() {
        binding.fullVideoUnderline.visibility = View.VISIBLE
        binding.eventVideoUnderline.visibility = View.GONE
    }
    private fun selectEventVideoTab() {
        binding.fullVideoUnderline.visibility = View.GONE
        binding.eventVideoUnderline.visibility = View.VISIBLE
    }

    // 영상이 아무것도 없을 시 저장된 영상이 없습니다. 표시되게
    private fun checkEmptyList() {
        if (storageAdapter.itemCount == 0) {
            binding.storageRecyclerView.visibility = View.GONE
            binding.emptyTextView.visibility = View.VISIBLE
        } else {
            binding.storageRecyclerView.visibility = View.VISIBLE
            binding.emptyTextView.visibility = View.GONE
        }
    }
}