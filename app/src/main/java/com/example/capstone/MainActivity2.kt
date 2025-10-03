package com.example.capstone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.capstone.databinding.ActivityMain2Binding
import androidx.preference.PreferenceManager

class MainActivity2 : AppCompatActivity() {

    private lateinit var binding: ActivityMain2Binding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var cameraFragment: CameraFragment
    private lateinit var mapFragment: MapFragment

    private var recordingService: RecordingService? = null
    private var isServiceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isServiceBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            recordingService = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 서비스에 바인딩 시작
        Intent(this, RecordingService::class.java).also { intent ->
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }

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
        observeRecordingService() // 서비스 상태를 관찰하는 함수 호출 추가
    }

    override fun onDestroy() {
        super.onDestroy()
        // 액티비티가 종료될 때 서비스 바인딩 해제
        if (isServiceBound) {
            unbindService(connection)
            isServiceBound = false
        }
    }

    private fun setupClickListeners() {
        binding.settingsBtn.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.storageBtn.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        // ▼▼▼▼▼ [수정] 카메라 버튼 클릭 리스너 수정 ▼▼▼▼▼
        binding.camera.setOnClickListener {
            // 권한 확인 로직 추가
            if (allPermissionsGranted()) {
                toggleRecordingService()
            } else {
                Toast.makeText(this, "카메라와 오디오 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
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
            recordingService?.toggleFlash()
        }
    }

    // ▼▼▼▼▼ [추가] 서비스를 제어하는 함수 추가 ▼▼▼▼▼
    private fun toggleRecordingService() {
        val intent = Intent(this, RecordingService::class.java)
        // 현재 녹화 상태에 따라 서비스에 다른 액션(명령)을 전달
        if (viewModel.isRecording.value == true) {
            intent.action = RecordingService.ACTION_STOP_RECORDING
        } else {
            intent.action = RecordingService.ACTION_START_RECORDING
        }
        // 인텐트를 통해 서비스를 시작하거나 명령을 전달
        startService(intent)
    }

    // ▼▼▼▼▼ [추가] 서비스의 녹화 상태를 관찰하는 함수 추가 ▼▼▼▼▼
    private fun observeRecordingService() {
        // 서비스의 isRecording LiveData를 관찰
        RecordingService.isRecording.observe(this) { isRecording ->
            // 서비스의 상태가 변경되면 ViewModel의 상태도 함께 업데이트
            viewModel.setIsRecording(isRecording)
        }
        RecordingService.isFlashOn.observe(this) { isOn ->
            updateFlashState(isOn)
        }
    }

    private fun observeViewModel() {
        viewModel.isMapVisible.observe(this) { isVisible ->
            syncUiToState()
        }

        // ViewModel의 isRecording 상태를 관찰(observe)
        viewModel.isRecording.observe(this) { isRecording ->
            if (isRecording) {
                // 녹화 중일 때 아이콘 (예: 중지 아이콘)
                binding.camera.setImageResource(R.drawable.camera_on)
            } else {
                // 녹화 중이 아닐 때 아이콘 (예: 녹화 아이콘)
                binding.camera.setImageResource(R.drawable.camera)
            }
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
        updateFlashState(RecordingService.isFlashOn.value ?: false)

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

    // 권한 확인 함수 및 상수
    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}