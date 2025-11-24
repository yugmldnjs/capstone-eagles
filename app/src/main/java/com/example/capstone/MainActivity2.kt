package com.example.capstone

import android.content.*
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import com.example.capstone.databinding.ActivityMain2Binding
import androidx.preference.PreferenceManager
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager
import com.example.capstone.ml.PotholeDetection
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter


class MainActivity2 : AppCompatActivity() {

    companion object {
        private const val TAG = "PotholeReceiver"
    }

    private lateinit var binding: ActivityMain2Binding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var mapFragment: MapFragment

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val powerSaveHandler = Handler(Looper.getMainLooper())
    private var powerSaveRunnable: Runnable? = null
    // 원래 화면 밝기 저장 변수
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var isPowerSavingActive = false

    private val potholeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d("PotholeReceiver", "onReceive: intent=$intent, action=${intent?.action}")

            // 액션 체크
            if (intent?.action != RecordingService.ACTION_POTHOLE_DETECTIONS) {
                Log.d("PotholeReceiver", "ignored action=${intent?.action}")
                return
            }

            // ★ API 33(Tiramisu)+ 에서는 클래스까지 넘겨주는 버전을 써야 안전함
            val detections: ArrayList<PotholeDetection> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableArrayListExtra(
                    "detections",
                    PotholeDetection::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<PotholeDetection>("detections")
            } ?: arrayListOf()

            Log.d("PotholeReceiver", "received detections size=${detections.size}")

            // 오버레이에 전달 (UI 스레드에서)
            runOnUiThread {
                binding.potholeOverlay.updateDetections(detections)
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            serviceBound = true

            Log.d("MainActivity2", "Service connected")

            // View가 완전히 그려진 후 카메라 초기화
            binding.viewFinder.post {
                binding.miniCamera.post {
                    recordingService?.setPreviewViews(binding.viewFinder, binding.miniCamera)

                    // ✅ 포트홀 감지 결과 콜백 등록
                    recordingService?.setPotholeListener { detections ->
                        runOnUiThread {
                            // 1) 카메라 위 박스 오버레이 업데이트 (기존 동작 유지)
                            binding.potholeOverlay.updateDetections(detections)

                            // 2) 지도용 포트홀 이벤트 트리거
                            //    - 가장 높은 score 하나만 보고
                            //    - score, 위치(cy) 기준으로 한 번 더 필터
                            val best = detections.maxByOrNull { it.score }

                            if (best != null &&
                                best.score >= 0.6f &&   // 필요하면 0.5 ~ 0.7 사이로 조절
                                best.cy > 0.5f          // 화면 아래쪽(내 자전거 앞)만 인정
                            ) {
                                // 현재 GPS 위치 기준으로 포트홀 추가
                                mapFragment.addPotholeFromCurrentLocationFromModel()
                            }
                        }
                    }

                    // 서비스 상태를 ViewModel에 동기화
                    viewModel.setRecordingState(recordingService?.isRecording() ?: false)
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            recordingService = null
            serviceBound = false
        }
    }

    private val recordingReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                RecordingService.ACTION_RECORDING_STARTED -> {
                    viewModel.setRecordingState(true)
                }
                RecordingService.ACTION_RECORDING_STOPPED -> {
                    viewModel.setRecordingState(false)
                }
                RecordingService.ACTION_RECORDING_SAVED -> {
                    val message = intent.getStringExtra("message") ?: "영상 저장 완료"
                    Toast.makeText(this@MainActivity2, message, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMain2Binding.inflate(layoutInflater)
        setContentView(binding.root)

        // 앱이 처음 시작될 때만 초기 프래그먼트 설정
        if (savedInstanceState == null) {
            mapFragment = MapFragment()
            supportFragmentManager.beginTransaction().apply {
                add(R.id.fragment_container, mapFragment, "MAP_FRAGMENT")
                hide(mapFragment)
            }.commit()
        } else {
            mapFragment = supportFragmentManager.findFragmentByTag("MAP_FRAGMENT") as MapFragment
        }

        // 서비스 시작 및 바인딩
        val serviceIntent = Intent(this, RecordingService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)

        // 브로드캐스트 리시버 등록
        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED)
            addAction(RecordingService.ACTION_RECORDING_STOPPED)
            addAction(RecordingService.ACTION_RECORDING_SAVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(recordingReceiver, filter)
        }

        // ★ 포트홀 감지 결과 리시버 등록
        val potholeFilter = IntentFilter().apply {
            addAction(RecordingService.ACTION_POTHOLE_DETECTIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(potholeReceiver, potholeFilter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(potholeReceiver, potholeFilter)
        }

        Log.d(TAG, "PotholeReceiver registered")

        setupClickListeners()
        observeViewModel()

        // 프래그먼트 뒤로가기 감지
        supportFragmentManager.addOnBackStackChangedListener {
            // backStackEntryCount가 0이라는 것은 모든 프래그먼트가 닫혔다는 의미임.
            if (supportFragmentManager.backStackEntryCount == 0) {
                // 버튼 다시 다 보여줘야함. (메인이니)
                binding.settingsBtn.visibility = View.VISIBLE
                binding.storageBtn.visibility = View.VISIBLE
                binding.camera.visibility = View.VISIBLE
                binding.mapBtn.visibility = View.VISIBLE
                binding.flashBtn.visibility = View.VISIBLE

                // 현재 뷰 상태에 맞게 속도계와 미니카메라도 복원
                syncUiToState()
            }
        }
    }

    private fun setupClickListeners() {
        binding.settingsBtn.setOnClickListener {
            //startActivity(Intent(this, SettingsActivity::class.java))
            showSettingsFragment()
        }
        binding.storageBtn.setOnClickListener {
            startActivity(Intent(this, StorageActivity::class.java))
        }
        binding.camera.setOnClickListener {
            toggleRecording()
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

        binding.miniCamera.setOnClickListener {
            // 미니 카메라를 클릭하면 메인 카메라 뷰로 전환합니다.
            showCameraView()
        }
    }

    private fun showSettingsFragment() {
        // 버튼들 다 숨겨버려!!!
        binding.settingsBtn.visibility = View.GONE
        binding.storageBtn.visibility = View.GONE
        binding.camera.visibility = View.GONE
        binding.mapBtn.visibility = View.GONE
        binding.flashBtn.visibility = View.GONE
        binding.speedTextView.visibility = View.GONE


        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, SettingsContainerFragment(), "SETTINGS")
            .addToBackStack("SETTINGS")
            .commit()
    }

    private fun toggleRecording() {
        Log.d("MainActivity2", "toggleRecording called, serviceBound=$serviceBound")

        if (!serviceBound || recordingService == null) {
            Log.e("MainActivity2", "Service not bound or null")
            Toast.makeText(this, "서비스 연결 중입니다. 잠시 후 다시 시도하세요.", Toast.LENGTH_SHORT).show()
            return
        }

        val isRecording = recordingService?.isRecording() ?: false
        Log.d("MainActivity2", "Current recording state: $isRecording")

        if (isRecording) {
            Log.d("MainActivity2", "Calling stopRecording")
            recordingService?.stopRecording()

            // 즉시 UI 업데이트 (브로드캐스트 오기 전에)
            binding.camera.postDelayed({
                val stillRecording = recordingService?.isRecording() ?: false
                if (!stillRecording) {
                    viewModel.setRecordingState(false)
                    Log.d("MainActivity2", "Recording confirmed stopped")
                } else {
                    Log.e("MainActivity2", "Recording failed to stop")
                    Toast.makeText(this, "녹화 중지 실패", Toast.LENGTH_SHORT).show()
                }
            }, 500)
        } else {
            Log.d("MainActivity2", "Calling startRecording")
            recordingService?.startRecording()

            // 즉시 UI 업데이트 (브로드캐스트 오기 전에)
            binding.camera.postDelayed({
                if (recordingService?.isRecording() == true) {
                    viewModel.setRecordingState(true)
                    Log.d("MainActivity2", "Recording confirmed started")
                } else {
                    Log.e("MainActivity2", "Recording failed to start")
                    Toast.makeText(this, "녹화 시작 실패. 권한을 확인하세요.", Toast.LENGTH_SHORT).show()
                }
            }, 500)
        }
    }

    private fun observeViewModel() {
        viewModel.isFlashOn.observe(this) { isOn ->
            updateFlashState(isOn)
            recordingService?.enableTorch(isOn)
        }
        viewModel.isMapVisible.observe(this) { isVisible ->
            syncUiToState()
        }

        viewModel.isRecording.observe(this) { isRecording ->
            if (isRecording) {
                binding.camera.setImageResource(R.drawable.camera_on)
                if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                    startPowerSavingTimer()
                }
            } else {
                binding.camera.setImageResource(R.drawable.camera)
                cancelPowerSaving()
            }
        }
    }

    // 절전 모드 타이머 시작
    private fun startPowerSavingTimer() {
        // 기존 타이머가 있다면 취소
        cancelPowerSaving()

        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val isEnabled = prefs.getBoolean("rec_dark_mode", false)

        if (isEnabled) {
            val timeString = prefs.getString("rec_dark_mode_time", "3") ?: "3"

            // "3"을 3초가 아닌 3분 (3 * 60 * 1000)으로 계산하도록 수정
            val delayMs = (timeString.toLongOrNull() ?: 3L) * 60 * 1000L

            powerSaveRunnable = Runnable {
                activatePowerSavingMode()
            }
            powerSaveHandler.postDelayed(powerSaveRunnable!!, delayMs)
        }
    }

    // 절전 모드 활성화 (화면 어둡게)
    private fun activatePowerSavingMode() {
        // 현재 밝기를 한 번만 저장
        if (!isPowerSavingActive) {
            // 현재 밝기를 저장 (이 값이 -1.0f 일지라도 그대로 저장)
            originalBrightness = window.attributes.screenBrightness
            val layoutParams = window.attributes
            layoutParams.screenBrightness = 0.01f
            window.attributes = layoutParams

            // 절전 모드 플래그 설정
            isPowerSavingActive = true
        }
    }

    // 절전 모드 취소 (타이머 중지 및 화면 밝기 복구)
    private fun cancelPowerSaving() {
        // 1. 타이머(Runnable)가 예약되어 있다면 취소
        if (powerSaveRunnable != null) {
            powerSaveHandler.removeCallbacks(powerSaveRunnable!!)
            powerSaveRunnable = null
        }

        if (isPowerSavingActive) {
            // 저장해둔 원래 밝기(시스템 기본값 -1.0f 포함)로 복원
            val layoutParams = window.attributes
            layoutParams.screenBrightness = originalBrightness
            window.attributes = layoutParams

            // 절전 모드 플래그 해제
            isPowerSavingActive = false
        }
    }

    // 지도 화면인 경우
    private fun showMapView() {
        binding.viewFinder.visibility = View.GONE
        supportFragmentManager.beginTransaction()
            .show(mapFragment)
            .commitAllowingStateLoss()
        viewModel.setMapVisible(true)
    }

    // 카메라 화면인 경우
    private fun showCameraView() {
        binding.viewFinder.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .hide(mapFragment)
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
            binding.mapBtn.setImageResource(R.drawable.main_camera)
            binding.settingsBtn.visibility = View.GONE
            binding.flashBtn.visibility = View.GONE
            binding.speedTextView.visibility = View.GONE

            val prefs = PreferenceManager.getDefaultSharedPreferences(this)
            val shouldShowMinimap = prefs.getBoolean("show_minimap_on_map", true)
            if (shouldShowMinimap) {
                binding.miniCamera.visibility = View.VISIBLE
                recordingService?.updateMiniPreviewVisibility(true)
            } else {
                binding.miniCamera.visibility = View.GONE
            }
        } else {
            binding.mapBtn.setImageResource(R.drawable.map)
            binding.settingsBtn.visibility = View.VISIBLE
            binding.flashBtn.visibility = View.VISIBLE
            binding.speedTextView.visibility = View.VISIBLE
            binding.miniCamera.visibility = View.GONE
            recordingService?.updateMiniPreviewVisibility(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            // ✅ 액티비티가 사라질 때 콜백 끊기
            recordingService?.setPotholeListener(null)
            unbindService(serviceConnection)
            serviceBound = false
        }
        try {
            unregisterReceiver(recordingReceiver)
        } catch (e: Exception) {
            // 이미 해제된 경우 무시
        }
        try {
            unregisterReceiver(potholeReceiver)
        } catch (e: Exception) {
            // 이미 해제된 경우 무시
        }
        cancelPowerSaving()
    }
}