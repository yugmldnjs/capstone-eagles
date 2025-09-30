package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.capstone.databinding.ActivityMain2Binding
import androidx.preference.PreferenceManager

class MainActivity2 : AppCompatActivity() {

    private lateinit var binding: ActivityMain2Binding
    private val viewModel: MainViewModel by viewModels()


    private lateinit var cameraFragment: CameraFragment
    private lateinit var mapFragment: MapFragment

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 앱이 처음 시작될 때만 초기 프래그먼트 설정
        if (savedInstanceState == null) {
            // 앱 최초 실행 시 -> 새로운 Fragment를 만들고 태그를 붙여 추가
            cameraFragment = CameraFragment()
            mapFragment = MapFragment()
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragment_container, cameraFragment, "CAMERA_FRAGMENT")
                add(R.id.fragment_container, mapFragment, "MAP_FRAGMENT")
                hide(mapFragment)
            }.commit()
        } else {
            // 화면 회전 후 -> FragmentManager가 복원한 Fragment를 태그로 찾음
            cameraFragment = supportFragmentManager.findFragmentByTag("CAMERA_FRAGMENT") as CameraFragment
            mapFragment = supportFragmentManager.findFragmentByTag("MAP_FRAGMENT") as MapFragment
        }

        // 함수화
        setupClickListeners()
        observeViewModel()
    }

    private fun setupClickListeners() {
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.storageBtn.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        binding.camera.setOnClickListener {
            if (viewModel.isMapVisible.value == true) {
                showCameraView()
            } else {
                viewModel.requestTakePhoto()
            }
        }
        binding.mapBtn.setOnClickListener {
            if (viewModel.isMapVisible.value == true) {
                showCameraView()
            } else {
                showMapView()
            }
        }
        binding.flashBtn.setOnClickListener {
            viewModel.toggleFlash()
        }
    }

    private fun observeViewModel() {
        viewModel.isFlashOn.observe(this) { isOn ->
            updateFlashState(isOn)
        }
        viewModel.isMapVisible.observe(this) { isVisible ->
            syncUiToState()
        }
    }

    // 지도 화면인 경우
    private fun showMapView() {
        supportFragmentManager.beginTransaction()
            .hide(cameraFragment)
            .show(mapFragment)
            .commit()
        viewModel.setMapVisible(true)
    }

    // 카메라 화면인 경우
    private fun showCameraView() {
        supportFragmentManager.beginTransaction()
            .hide(mapFragment)
            .show(cameraFragment)
            .commit()
        viewModel.setMapVisible(false)
    }

    // 플래시 상태 따라 아이콘 변경
    private fun updateFlashState(isOn: Boolean) {
        if (isOn) {
            binding.flashBtn.setImageResource(R.drawable.flash_on)
        } else {
            binding.flashBtn.setImageResource(R.drawable.flashh)
        }
    }

    private fun syncUiToState() {
        updateFlashState(viewModel.isFlashOn.value ?: false)

        if (viewModel.isMapVisible.value == true) {
            binding.settingsBtn.visibility = View.GONE
            binding.flashBtn.visibility = View.GONE
            binding.speedTextView.visibility = View.GONE

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val shouldShowMinimap = prefs.getBoolean("show_minimap_on_map", true)
            binding.miniCamera.visibility = if (shouldShowMinimap) View.VISIBLE else View.GONE
        } else {
            binding.settingsBtn.visibility = View.VISIBLE
            binding.flashBtn.visibility = View.VISIBLE
            binding.speedTextView.visibility = View.VISIBLE
            binding.miniCamera.visibility = View.GONE
        }
    }
}