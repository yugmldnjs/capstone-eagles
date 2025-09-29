package com.example.capstone

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.capstone.databinding.ActivityMain2Binding
import androidx.preference.PreferenceManager

class MainActivity2 : AppCompatActivity() {

    private lateinit var binding: ActivityMain2Binding
    private lateinit var viewModel: MainViewModel
    // private var isMapVisible = false // 이 변수를 삭제하고 ViewModel에서 관리합니다.

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 하단 소프트키 투명화 (카메라 풀화면을 위해) -> 이거 대신 매니페스트에서 설정했음
        //WindowCompat.setDecorFitsSystemWindows(window, false)



        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        viewModel = ViewModelProvider(this).get(MainViewModel::class.java)

        // 앱이 처음 시작될 때만 초기 프래그먼트 설정
        if (savedInstanceState == null) {
            replaceFragment(CameraFragment())
        }

        // 화면이 다시 만들어질 때 ViewModel의 상태에 맞게 UI를 복원 (가로 <-> 세로 전환)
        syncUiToState()



        // --- 버튼 클릭 이동 ---
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.storageBtn.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }

        binding.camera.setOnClickListener {
            if (viewModel.isMapVisible) {
                showCameraView()
            }
            viewModel.isRecording = !viewModel.isRecording
            updateRecordingState()
        }

        binding.mapBtn.setOnClickListener {
            if (viewModel.isMapVisible) {
                showCameraView()
            } else {
                showMapView()
            }
        }

        binding.flashBtn.setOnClickListener {
            viewModel.isFlashOn = !viewModel.isFlashOn
            updateFlashState()
        }
    }

    // 지도 화면인 경우
    private fun showMapView() {
        replaceFragment(MapFragment())
        viewModel.isMapVisible = true // ViewModel 상태 변경
        syncUiToState()
    }

    // 카메라 화면인 경우
    private fun showCameraView() {
        replaceFragment(CameraFragment())
        viewModel.isMapVisible = false // ViewModel 상태 변경
        syncUiToState()
    }

    private fun replaceFragment(fragment: Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    // ViewModel의 현재 상태에 따라 모든 UI 업데이트 (지도 화면인지, 메인 화면인지에 따라)
    private fun syncUiToState() {
        updateRecordingState()
        updateFlashState()

        if (viewModel.isMapVisible) {
            binding.settingsBtn.visibility = View.GONE
            binding.flashBtn.visibility = View.GONE
            binding.speedTextView.visibility = View.GONE

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val shouldShowMinimap = prefs.getBoolean("show_minimap_on_map", true)
            if (shouldShowMinimap) {
                binding.miniCamera.visibility = View.VISIBLE
            } else {
                binding.miniCamera.visibility = View.GONE
            }
        } else {
            binding.settingsBtn.visibility = View.VISIBLE
            binding.flashBtn.visibility = View.VISIBLE
            binding.speedTextView.visibility = View.VISIBLE
            binding.miniCamera.visibility = View.GONE
        }
    }


    // 녹화 상태에 따라 녹화 버튼 아이콘을 업데이트
    private fun updateRecordingState() {
        if (viewModel.isRecording) {
            binding.camera.setImageResource(R.drawable.camera_on)
        } else {
            binding.camera.setImageResource(R.drawable.camera)
        }
    }

    // 플래시 상태 따라 아이콘 변경
    private fun updateFlashState() {
        if (viewModel.isFlashOn) {
            binding.flashBtn.setImageResource(R.drawable.flash_on)
        } else {
            binding.flashBtn.setImageResource(R.drawable.flashh)
        }
    }
}