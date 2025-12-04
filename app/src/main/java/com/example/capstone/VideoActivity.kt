package com.example.capstone

import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.example.capstone.databinding.ActivityVideoBinding
import java.io.File
import androidx.media3.common.C

@UnstableApi
class VideoActivity : AppCompatActivity() {
    private lateinit var binding: ActivityVideoBinding
    private var exoPlayer: ExoPlayer? = null

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
            setupPlayer(videoPath)
        } else {
            Toast.makeText(this, "영상을 불러올 수 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun setupPlayer(videoPath: String) {
        // ExoPlayer 초기화
        exoPlayer = ExoPlayer.Builder(this).build()
        binding.videoPlayerView.player = exoPlayer

        val videoUri = Uri.fromFile(File(videoPath))

        // SRT 파일 경로 생성 (video.mp4 -> video.srt)
        val srtPath = videoPath.replace(".mp4", ".srt")
        val srtFile = File(srtPath)

        // MediaItem 생성
        val mediaItemBuilder = MediaItem.Builder().setUri(videoUri)

        // SRT 파일이 존재하면 자막 추가
        if (srtFile.exists()) {
            val srtUri = Uri.fromFile(srtFile)
            mediaItemBuilder.setSubtitleConfigurations(
                listOf(
                    MediaItem.SubtitleConfiguration.Builder(srtUri)
                        .setMimeType(MimeTypes.APPLICATION_SUBRIP)
                        .setLanguage("ko")
                        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        .build()
                )
            )
        }

        val mediaItem = mediaItemBuilder.build()

        // 영상 설정 및 재생
        exoPlayer?.apply {
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true // 자동 재생
        }
    }

    override fun onStop() {
        super.onStop()
        // 액티비티가 보이지 않을 때 일시정지
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        // ExoPlayer 리소스 해제
        exoPlayer?.release()
        exoPlayer = null
    }
}