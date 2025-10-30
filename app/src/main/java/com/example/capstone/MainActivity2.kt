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
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowManager


class MainActivity2 : AppCompatActivity(), LocationListener {

    private lateinit var binding: ActivityMain2Binding
    private val viewModel: MainViewModel by viewModels()

    // 속도계 관련 변수
    private lateinit var locationManager: LocationManager
    private val locationPermissionCode = 2

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
            registerReceiver(recordingReceiver, filter)  // 경고뜸
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


        checkLocationPermission()  // 속도계 위치 권한
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

  /*  private fun showStorageFragment() {
        binding.settingsBtn.visibility = View.GONE
        binding.storageBtn.visibility = View.GONE
        binding.camera.visibility = View.GONE
        binding.mapBtn.visibility = View.GONE
        binding.flashBtn.visibility = View.GONE
        binding.speedTextView.visibility = View.GONE

        supportFragmentManager.beginTransaction()
            .add(R.id.fragment_container, StorageContainerFragment(), "STORAGE")  // 여기 add로 해야함!! 그래서 녹화 안끊겨
            .addToBackStack("STORAGE")
            .commit()
    }*/


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
            .commit()
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

    private fun checkLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), locationPermissionCode)
        } else {
            startLocationUpdates()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == locationPermissionCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        } else {
            Toast.makeText(this, "속도 측정을 위해 위치 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLocationUpdates() {
        if (!::locationManager.isInitialized) {
            locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            // 1초마다, 0미터 이동 시 업데이트
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, this)
        }
    }

    // LocationListener 부분

    override fun onLocationChanged(location: Location) {
        val speedKmh = location.speed * 3.6  // 단위 : km/h
        binding.speedTextView.text = String.format("%.1f", speedKmh)
    }

    // 사용자가 GPS를 껐을 때 GPS 켜달라고 해야함. 속도가 GPS 기반 측정이라 끄면 안됨.
    override fun onProviderDisabled(provider: String) {
        Toast.makeText(this, "속도 측정을 위해 GPS를 켜주세요.", Toast.LENGTH_LONG).show()
        binding.speedTextView.text = "--"
    }

    // 사용자가 GPS를 다시 켰을 때
    override fun onProviderEnabled(provider: String) {
        Toast.makeText(this, "GPS가 다시 연결되었습니다.", Toast.LENGTH_SHORT).show()
    }


    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startLocationUpdates()
        }
        if (viewModel.isRecording.value == true) {
            startPowerSavingTimer()
        }

    }

    override fun onPause() {
        super.onPause()
        if (::locationManager.isInitialized) {
            locationManager.removeUpdates(this)
        }
        cancelPowerSaving()
    }

    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {

    }

    // 화면 터치했을 경우 다시 밝아지게
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev?.action == MotionEvent.ACTION_DOWN) {
            // 녹화 중에만 화면 터치에 반응
            if (viewModel.isRecording.value == true) {

                cancelPowerSaving()
                startPowerSavingTimer()
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
        try {
            unregisterReceiver(recordingReceiver)
        } catch (e: Exception) {
            // 이미 해제된 경우 무시
        }
        cancelPowerSaving()
    }



}
