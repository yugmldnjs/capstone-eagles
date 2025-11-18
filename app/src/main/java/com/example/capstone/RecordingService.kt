package com.example.capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.text.SimpleDateFormat
import java.util.*
import android.location.Location
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices


class RecordingService : Service(), LifecycleOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    private val binder = LocalBinder()

    private lateinit var fusedLocationClient: FusedLocationProviderClient  // 위치 정보 가져오기

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var mainPreviewView: PreviewView? = null
    private var miniPreviewView: PreviewView? = null
    private var currentPreview: Preview? = null

    inner class LocalBinder : Binder() {
        fun getService(): RecordingService = this@RecordingService
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        Log.d(TAG, "RecordingService onCreate()")
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("카메라 준비 중"))
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun setPreviewViews(mainPreview: PreviewView, miniPreview: PreviewView) {
        Log.d(TAG, "setPreviewViews called")
        this.mainPreviewView = mainPreview
        this.miniPreviewView = miniPreview

        // UI가 완전히 준비될 때까지 약간 대기
        mainPreview.post {
            if (cameraProvider != null) {
                Log.d(TAG, "Camera provider already initialized, binding camera")
                bindCamera()
            } else {
                Log.d(TAG, "Initializing camera for the first time")
                initializeCamera()
            }
        }
    }

    fun switchPreviewTarget(useMiniPreview: Boolean) {
        Log.d(TAG, "switchPreviewTarget: useMiniPreview=$useMiniPreview")

        val preview = currentPreview ?: return

        if (useMiniPreview) {
            miniPreviewView?.let {
                preview.setSurfaceProvider(it.surfaceProvider)
                Log.d(TAG, "Preview switched to mini view")
            }
        } else {
            mainPreviewView?.let {
                preview.setSurfaceProvider(it.surfaceProvider)
                Log.d(TAG, "Preview switched to main view")
            }
        }
    }

    fun updateMiniPreviewVisibility(isVisible: Boolean) {
        Log.d(TAG, "updateMiniPreviewVisibility: $isVisible")
        switchPreviewTarget(isVisible)
    }

    private fun initializeCamera() {
        Log.d(TAG, "initializeCamera() called")
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                Log.d(TAG, "CameraProvider obtained successfully")
                bindCamera()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get CameraProvider", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val cameraProvider = this.cameraProvider ?: run {
            Log.e(TAG, "CameraProvider is null")
            return
        }
        val mainPreviewView = this.mainPreviewView ?: run {
            Log.e(TAG, "MainPreviewView is null")
            return
        }

        Log.d(TAG, "bindCamera called, isRecording=${recording != null}")

        // Preview는 1개만 생성
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(mainPreviewView.surfaceProvider)
        currentPreview = preview
        Log.d(TAG, "Single preview created")

        // VideoCapture는 한 번만 생성
        if (videoCapture == null) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            Log.d(TAG, "VideoCapture created")
        } else {
            Log.d(TAG, "VideoCapture already exists")
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            Log.d(TAG, "Camera unbound")

            // Preview 1개 + VideoCapture 1개만 바인딩
            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                preview,
                videoCapture
            )
            Log.d(TAG, "Camera bound successfully (Preview + VideoCapture)")
            updateNotification("카메라 대기 중")
        } catch (e: Exception) {
            Log.e(TAG, "Camera binding failed", e)
        }
    }

    fun startRecording() {
        Log.d(TAG, "startRecording() called")

        val videoCapture = this.videoCapture ?: run {
            Log.e(TAG, "VideoCapture is null")
            return
        }

        if (recording != null) {
            Log.w(TAG, "Already recording")
            return
        }

        // 1. 위치 권한이 있는지 먼저 확인합니다.
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasLocationPermission) {
            // 2. 권한이 있으면 현재 위치를 요청합니다. (비동기)
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    if (location != null) {
                        Log.d(TAG, "위치 확보 성공: ${location.latitude}, ${location.longitude}")
                        // 위치를 찾았으면 위치 정보를 포함해서 녹화 시작
                        startRecordingInternal(videoCapture, location)
                    } else {
                        Log.w(TAG, "위치 정보 null (GPS 미수신 등)")
                        // 위치를 못 찾았으면 그냥 녹화 시작
                        startRecordingInternal(videoCapture, null)
                    }
                }.addOnFailureListener {
                    Log.e(TAG, "위치 정보 요청 실패", it)
                    startRecordingInternal(videoCapture, null)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "위치 권한 보안 예외", e)
                startRecordingInternal(videoCapture, null)
            }
        } else {
            // 3. 권한이 없으면 바로 녹화 시작 (위치 없음)
            Log.w(TAG, "위치 권한 없음")
            startRecordingInternal(videoCapture, null)
        }
    }

    // [수정됨] 실제 녹화를 수행하는 내부 함수
    private fun startRecordingInternal(videoCapture: VideoCapture<Recorder>, location: Location?) {
        Log.d(TAG, "startRecordingInternal - Location included: ${location != null}")

        val name = "Blackbox-${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
            .format(System.currentTimeMillis())}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackboxVideos")
            }
        }

        // 1. Builder를 먼저 만듭니다.
        val outputOptionsBuilder = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)

        // 2. ★★★ 위치 정보가 있다면 Builder에 설정합니다 (여기가 바뀐 부분) ★★★
        if (location != null) {
            outputOptionsBuilder.setLocation(location)
        }

        // 3. 설정을 다 넣은 뒤에 build()를 호출합니다.
        val mediaStoreOutputOptions = outputOptionsBuilder.build()

        val audioPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)

        try {
            // 4. 생성된 옵션으로 녹화 준비 (여기서 setLocation을 호출하지 않습니다)
            val pendingRecording = videoCapture.output
                .prepareRecording(this, mediaStoreOutputOptions)

            // 녹화 시작
            recording = pendingRecording
                .apply {
                    if (audioPermission == PackageManager.PERMISSION_GRANTED) {
                        withAudioEnabled()
                    }
                }
                .start(ContextCompat.getMainExecutor(this)) { recordEvent ->
                    when (recordEvent) {
                        is VideoRecordEvent.Start -> {
                            updateNotification("녹화 중...")
                            sendBroadcast(Intent(ACTION_RECORDING_STARTED))
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                val msg = "영상 저장 완료: ${recordEvent.outputResults.outputUri}"
                                Log.d(TAG, msg)
                                sendBroadcast(Intent(ACTION_RECORDING_SAVED).apply {
                                    putExtra("message", msg)
                                })
                            } else {
                                Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                            }
                            recording = null
                            updateNotification("카메라 대기 중")
                            sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
                        }
                    }
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            recording = null
        }
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording() called")

        val currentRecording = recording
        if (currentRecording == null) {
            Log.w(TAG, "No active recording to stop")
            return
        }

        Log.d(TAG, "Stopping recording...")
        try {
            currentRecording.stop()
            Log.d(TAG, "Recording.stop() called successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
            recording = null
            updateNotification("카메라 대기 중")
            sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
        }
    }

    fun isRecording(): Boolean {
        val result = recording != null
        Log.d(TAG, "isRecording() = $result")
        return result
    }

    fun enableTorch(enable: Boolean) {
        camera?.cameraControl?.enableTorch(enable)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "블랙박스 녹화 서비스",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("블랙박스")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.camera)
            .setOngoing(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity2::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        recording?.stop()
        cameraProvider?.unbindAll()
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        const val ACTION_RECORDING_STARTED = "com.example.capstone.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.capstone.RECORDING_STOPPED"
        const val ACTION_RECORDING_SAVED = "com.example.capstone.RECORDING_SAVED"
    }
}