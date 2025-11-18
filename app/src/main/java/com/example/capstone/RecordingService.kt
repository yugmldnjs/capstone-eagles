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
import android.location.Location
import android.net.Uri
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
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.example.capstone.database.BikiDatabase
import com.example.capstone.database.EventDao
import com.example.capstone.database.EventEntity
import com.example.capstone.worker.EventExtractionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class RecordingService : Service(), LifecycleOwner, ImpactListener {
    private val lifecycleRegistry = LifecycleRegistry(this)

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry
    private val binder = LocalBinder()
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var mainPreviewView: PreviewView? = null
    private var miniPreviewView: PreviewView? = null
    private var currentPreview: Preview? = null
    private lateinit var sensorHandler: SensorHandler
    private var currentVideoUri: Uri? = null
    private var currentRecordingStartTime: Long = 0
    var currentLocation: Location? = null
    var currentSpeed: Float = 0f
    private var lastImpactTimestamp: Long = 0
    private lateinit var eventDao: EventDao
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

        val database = BikiDatabase.getDatabase(this)
        eventDao = database.eventDao()

        // --- SensorHandler 인스턴스 생성 ---
        sensorHandler = SensorHandler(this, this)


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
            Log.e(TAG, "VideoCapture is null - camera not initialized")
            return
        }

        if (recording != null) {
            Log.w(TAG, "Already recording")
            return
        }

        Log.d(TAG, "Preparing to start recording...")

        currentRecordingStartTime = System.currentTimeMillis()

        val name = "Blackbox-${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
            .format(currentRecordingStartTime)}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackboxVideos/Full")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val audioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )

        Log.d(TAG, "Audio permission granted: ${audioPermission == PackageManager.PERMISSION_GRANTED}")

        try {
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
                            updateNotification("녹화 중...")
                            sendBroadcast(Intent(ACTION_RECORDING_STARTED))
                            Log.d(TAG, "녹화 시작 성공!")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                currentVideoUri = recordEvent.outputResults.outputUri

                                val msg = "영상 저장 완료: ${currentVideoUri}"
                                Log.d(TAG, msg)
                                sendBroadcast(Intent(ACTION_RECORDING_SAVED).apply {
                                    putExtra("message", msg)
                                })

                                // ✅ 핵심: 이 녹화 세션의 pending 이벤트들 업데이트
                                updatePendingEventsWithUri(
                                    currentRecordingStartTime,
                                    currentVideoUri!!
                                )
                                // WorkManager 예약
                                scheduleEventExtraction(currentVideoUri!!)



                            } else {
                                Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                            }
                            recording = null
                            currentRecordingStartTime = 0
                            currentVideoUri = null
                            updateNotification("카메라 대기 중")
                            sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
                        }
                    }
                }

            Log.d(TAG, "Recording object created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            recording = null
            currentRecordingStartTime = 0
        }
        sensorHandler.start()
        //LogToFileHelper.startLogging(this, "SensorLog")
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording() called")
        sensorHandler.stop()
        LogToFileHelper.stopLogging()

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

    override fun onImpactDetected(accelData: FloatArray, gyroData: FloatArray?) {
        val timestamp = System.currentTimeMillis()

        if (timestamp - lastImpactTimestamp < 3000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신

        // currentRecordingStartTime이 0이면 아직 녹화가 시작되지 않은 것이므로 무시
        if (currentRecordingStartTime == 0L) {
            Log.w(TAG, "충격이 감지되었으나 녹화 시작 전이므로 이벤트를 무시합니다.")
            return
        }

        val event = EventEntity(
            timestamp = timestamp,
            recordingStartTimestamp = currentRecordingStartTime,
            type = "impact",
            latitude = currentLocation?.latitude,
            longitude = currentLocation?.longitude,
            speed = currentSpeed,
            accelerometerX = accelData[0],
            accelerometerY = accelData[1],
            accelerometerZ = accelData[2],
            gyroX = gyroData?.get(0),
            gyroY = gyroData?.get(1),
            gyroZ = gyroData?.get(2),
            videoUri = null,  // 아직 URI 모름 (Finalize에서 업데이트)
            extractedVideoPath = null,  // 아직 추출 안 됨
            status = "pending"  // 추출 대기 상태
        )
        Log.d(TAG, "latitude: ${currentLocation?.latitude}, longitude: ${currentLocation?.longitude}")

        // DB에 비동기로 저장 (0.1초 이내)
        // DB에 비동기로 저장 (lifecycleScope 사용)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                eventDao.insert(event)
                Log.d(TAG, "✅ DB 저장 성공")

            } catch (e: Exception) {
                Log.e(TAG, "❌ DB 저장 실패", e)
            }
        }

        Log.d(TAG, "⚡ 충격 이벤트 마커 저장: $timestamp")
        sendBroadcast(Intent(ACTION_RECORDING_SAVED).apply {
            putExtra("message", "충격 이벤트가 감지되었습니다.")
        })

        // 사용자에게 즉시 알림
        //showImpactNotification(timestamp)
    }

    // ✅ Finalize에서 호출: URI로 pending 이벤트들 업데이트
    private fun updatePendingEventsWithUri(recordingStartTimestamp: Long, uri: Uri) {
        lifecycleScope.launch(Dispatchers.IO) {
            // 해당 녹화 세션의 이벤트들 찾기
            val pendingEvents = eventDao.getPendingExtractions()
                .filter { it.recordingStartTimestamp == recordingStartTimestamp }

            // URI로 업데이트
            pendingEvents.forEach { event ->
                eventDao.update(event.copy(
                    videoUri = uri.toString()
                ))
            }

            Log.d(TAG, "✅ ${pendingEvents.size}개 이벤트 URI 업데이트 완료")
        }
    }

    private fun scheduleEventExtraction(uri: Uri) {
        val workRequest = OneTimeWorkRequestBuilder<EventExtractionWorker>()
            .setInputData(
                workDataOf("video_uri" to uri.toString())
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)  // 배터리 20% 이상일 때만
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
        Log.d(TAG, "📋 이벤트 추출 작업 예약: $uri")
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        recording?.stop()
        cameraProvider?.unbindAll()
        sensorHandler.releaseListener()
        sensorHandler.stop()
        LogToFileHelper.stopLogging()
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