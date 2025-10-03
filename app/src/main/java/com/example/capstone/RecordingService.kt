package com.example.capstone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService // 1. Service 대신 LifecycleService를 상속합니다.
import androidx.lifecycle.MutableLiveData
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// 2. Service() 대신 LifecycleService()를 상속받도록 변경
class RecordingService : LifecycleService() {
    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }
    private val binder = LocalBinder()

    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private var preview: Preview? = null

    // 경쟁 상태 해결을 위한 변수들
    @Volatile private var isCameraReady = false
    private var pendingSurfaceProvider: Preview.SurfaceProvider? = null

    // UI에 녹화 상태를 알리기 위한 LiveData
    companion object {
        val isRecording = MutableLiveData<Boolean>(false)
        val isFlashOn = MutableLiveData<Boolean>(false) // 플래시 상태 LiveData 추가
        const val ACTION_START_RECORDING = "ACTION_START_RECORDING"
        const val ACTION_STOP_RECORDING = "ACTION_STOP_RECORDING"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "RecordingChannel"
        private const val TAG = "RecordingService" // TAG를 서비스 이름으로 변경
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
    }

    // ▼▼▼ [추가] 플래시를 직접 제어하는 함수 ▼▼▼
    fun toggleFlash() {
        // 현재 플래시 상태를 반전시킴
        val newState = !(isFlashOn.value ?: false)
        // LiveData 상태 업데이트
        isFlashOn.postValue(newState)
        // 실제 카메라 하드웨어 제어
        camera?.cameraControl?.enableTorch(newState)
    }

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()
        startCamera()
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // onStartCommand는 super.onStartCommand를 먼저 호출해주는 것이 좋습니다.
        super.onStartCommand(intent, flags, startId)

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START_RECORDING -> {
                toggleRecording()
                isRecording.postValue(true)
            }
            ACTION_STOP_RECORDING -> {
                toggleRecording()
                isRecording.postValue(false)
                stopSelf()
            }
        }
        return START_STICKY
    }

    fun attachPreview(surfaceProvider: Preview.SurfaceProvider) {
        pendingSurfaceProvider = surfaceProvider
        if (isCameraReady) {
            bindCameraUseCases()
        }
    }

    fun detachPreview() {
        pendingSurfaceProvider = null
        if (preview != null) {
            preview = null
            bindCameraUseCases()
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get() // cameraProvider를 멤버 변수에 할당
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)

            isCameraReady = true // 카메라 준비 완료!
            if (pendingSurfaceProvider != null) {
                bindCameraUseCases() // 대기중인 미리보기 요청 처리
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases() {
        val cameraProvider = this.cameraProvider ?: return
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
        try {
            cameraProvider.unbindAll()
            val useCases = mutableListOf<UseCase>()
            videoCapture?.let { useCases.add(it) }

            pendingSurfaceProvider?.let { provider ->
                preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(provider)
                }
                useCases.add(preview!!)
            }

            if (useCases.isNotEmpty()) {
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, *useCases.toTypedArray()
                )
            }
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
        }
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        // 앱 충돌 방지 안전장치
        if (!isCameraReady) {
            Toast.makeText(this, "카메라 준비 중입니다.", Toast.LENGTH_SHORT).show()
            return
        }
        val videoCapture = this.videoCapture ?: return

        if (recording != null) {
            recording?.stop()
            recording = null
            return
        }

        val name = "Blackbox-${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackbox")
            }
        }

        // 6. 모든 requireActivity()와 requireContext()를 this로 변경
        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(this.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)

        recording = videoCapture.output
            .prepareRecording(this, mediaStoreOutputOptions)
            .apply {
                if (audioPermission == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Toast.makeText(this, "녹화 시작", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "영상 저장 완료: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID, "녹화 서비스 채널", NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("영상 녹화 중")
            .setContentText("블랙박스 앱이 백그라운드에서 실행 중입니다.")
            .setSmallIcon(R.drawable.camera_on) // 아이콘을 drawable 폴더에 추가해야 합니다.
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}