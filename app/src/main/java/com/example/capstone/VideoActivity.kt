package com.example.capstone

import android.net.Uri
import android.os.Bundle
import android.widget.MediaController
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.capstone.databinding.ActivityVideoBinding

class VideoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        binding = ActivityVideoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        val videoPath = intent.getStringExtra("VIDEO_PATH")
        if (videoPath != null) {
            val videoView = binding.videoPlayerView
            val videoUri = Uri.parse(videoPath)

            // 영상 컨트롤러(재생, 정지 등) 추가
            val mc = MediaController(this)
            videoView.setMediaController(mc)

            // VideoView에 URI 설정
            videoView.setVideoURI(videoUri)
            videoView.requestFocus()

            // 영상 재생 시작
            videoView.start()
        } else {
            Toast.makeText(this, "영상을 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish() // 비디오 경로가 없으면 액티비티 종료
        }
    }
}