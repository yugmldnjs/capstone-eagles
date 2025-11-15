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
import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

class MainActivity2 : AppCompatActivity() {

    private lateinit var binding: ActivityMain2Binding
    private val viewModel: MainViewModel by viewModels()

    // private lateinit var cameraFragment: CameraFragment
    private lateinit var mapFragment: MapFragment

    private var recordingService: RecordingService? = null
    private var serviceBound = false

    private val powerSaveHandler = Handler(Looper.getMainLooper())
    private var powerSaveRunnable: Runnable? = null
    // 원래 화면 밝기 저장 변수
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var isPowerSavingActive = false

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

    private val REQUIRED_PERMISSIONS =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS 
            )
        } else {
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
            )
        }

    // 권한 요청 런처
    private val requestPermissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val allGranted = permissions.entries.all { it.value }

            if (allGranted) {
                startAndBindRecordingService()
            } else {
                Log.e("MainActivity2", "권한 거부")
                Toast.makeText(this, "앱 실행에 필요한 모든 권한이 허용되어야 합니다.", Toast.LENGTH_LONG).show()
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

        hideNavigationBar() // 네비게이션 바 숨기는 함수

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
       /* val serviceIntent = Intent(this, RecordingService::class.java)
        startService(serviceIntent)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)*/

        checkAndRequestPermissions()  // 권한 체크

        // 브로드캐스트 리시버 등록
        val filter = IntentFilter().apply {
            addAction(RecordingService.ACTION_RECORDING_STARTED)
            addAction(RecordingService.ACTION_RECORDING_STOPPED)
            addAction(RecordingService.ACTION_RECORDING_SAVED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(recordingReceiver, filter)
        }

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

    private fun hideNavigationBar() {
        // API 30 (Android 11) 이상
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowCompat.setDecorFitsSystemWindows(window, false)

            val controller = WindowInsetsControllerCompat(window, binding.root)

            controller.hide(WindowInsetsCompat.Type.navigationBars())

            // 사용자가 스와이프할 때만 시스템 바가 잠시 나타나도록
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        } else {
            // API 30 미만
            // 'DEPRECATION' 경고 무시
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun checkAndRequestPermissions() {
        val allPermissionsGranted = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allPermissionsGranted) {
            // 모든 권한이 이미 다 허용이면 안 물어보고 바로 시작
            Log.d("MainActivity2", "모든 권한이 이미 허용되어 있습니다. 서비스 시작.")
            startAndBindRecordingService()
        } else {
            Log.d("MainActivity2", "필수 권한을 요청합니다.")
            requestPermissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun startAndBindRecordingService() {
        if (serviceBound) return

        val serviceIntent = Intent(this, RecordingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)}




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

        // 미니카메라 드래그 처리 함수 (사용자 원하는 위치로 이동 위해)
        setupDraggableMiniCamera()
    }

    private fun setupDraggableMiniCamera() {
        var initialViewX = 0f
        var initialViewY = 0f
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false
        val touchSlop = android.view.ViewConfiguration.get(this).scaledTouchSlop

        binding.miniCamera.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // 터치 시작 위치와 뷰의 현재 위치 저장
                    initialViewX = view.x
                    initialViewY = view.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY

                    // 살짝 움직인거는 터치로,
                    if (!isDragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                        isDragging = true
                    }

                    if (isDragging) {
                        val parent = view.parent as View
                        val maxX = parent.width - view.width
                        val maxY = parent.height - view.height

                        // 카메라가 화면 밖으로 안나가게 제한둔거
                        val newX = (initialViewX + dx).coerceIn(0f, maxX.toFloat())
                        val newY = (initialViewY + dy).coerceIn(0f, maxY.toFloat())

                        view.x = newX
                        view.y = newY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (isDragging) {
                        isDragging = false
                    } else {
                        // 드래그가 아니었다면 터치한거임.
                        view.performClick()
                    }
                    true // 이벤트 종료함
                }
                else -> false
            }
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
            unbindService(serviceConnection)
            serviceBound = false
        }

        // 백그라운드에서도 앱 계속 돌아가지 않도록 프로세스 제대로 파괴함.
        Log.d("MainActivity2", "onDestroy");
        val serviceIntent = Intent(this, RecordingService::class.java)
        stopService(serviceIntent)  // 앱 종료했을 때 제대로 Destroy되도록

        try {
            unregisterReceiver(recordingReceiver)
        } catch (e: Exception) {
            // 이미 해제된 경우 무시
        }
        cancelPowerSaving()
    }
}