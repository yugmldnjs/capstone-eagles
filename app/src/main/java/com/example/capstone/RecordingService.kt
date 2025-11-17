package com.example.capstone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.os.Binder
import android.os.Build
import android.os.IBinder
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

//data class ImpactInfo(
//    val sourceFile: File,        // 충격 발생 시 녹화 중이던 파일
//    val impactTimestamp: Long    // 충격 발생 시점의 정확한 시간 (ms)
//)
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
    // 사고 영상 추출을 담당할 클래스의 인스턴스 (예시)
    //private lateinit var videoProcessor: VideoProcessor // <-- 사고 영상 추출 클래스
    private var currentRecordingFile: File? = null
    private var currentRecordingStartTime: Long = 0  // 추가
    //private val impactInfoList = mutableListOf<ImpactInfo>()
    var currentLocation: Location? = null
    var currentSpeed: Float = 0f
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

        // --- SensorHandler 인스턴스 생성 ---
        sensorHandler = SensorHandler(this, this)
        // 사고 영상 추출 클래스 초기화 (예시)
        //videoProcessor = VideoProcessor(this) // Context가 필요하다면 전달

        val database = BikiDatabase.getDatabase(this)
        eventDao = database.eventDao()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("카메라 준비 중"))
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }
    // ImpactListener 인터페이스의 실제 동작을 여기서 구현
//    override fun onImpactDetected() {
//        // 이 메서드는 SensorHandler에서 충격이 감지될 때마다 호출됩니다.
//        Log.w(TAG, "onImpactDetected 콜백 수신! 사고 영상 추출 로직을 실행합니다.")
//
//        // --- 여기서 사고 영상 추출 로직을 호출합니다. ---
//        // 예시: 현재 녹화 파일 정보와 함께 추출 명령
//        val currentFile = /* 현재 녹화 중인 파일 정보 가져오기 */
//            videoProcessor.extractImpactVideo(currentFile, 15, 15) // 예: 사고 전 15초, 후 15초 추출
//    }


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
            .format(System.currentTimeMillis())}.mp4"

        // ✅ FileOutputOptions로 변경
        currentRecordingFile = File(
            getExternalFilesDir("recordings"),  // 또는 getExternalFilesDir(null)
            name
        ).apply {
            parentFile?.mkdirs()
        }

        val fileOutputOptions = FileOutputOptions.Builder(currentRecordingFile!!)
            .build()

        val audioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )

        Log.d(TAG, "Audio permission granted: ${audioPermission == PackageManager.PERMISSION_GRANTED}")

        try {
            recording = videoCapture.output
                .prepareRecording(this, fileOutputOptions)
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
                                val finalFile = currentRecordingFile

                                val msg = "영상 저장 완료: ${recordEvent.outputResults.outputUri}"
                                Log.d(TAG, msg)
                                sendBroadcast(Intent(ACTION_RECORDING_SAVED).apply {
                                    putExtra("message", msg)
                                })

                                // WorkManager 예약
                                finalFile?.let { scheduleEventExtraction(it) }

                            } else {
                                Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                            }
                            recording = null
                            currentRecordingFile = null
                            updateNotification("카메라 대기 중")
                            sendBroadcast(Intent(ACTION_RECORDING_STOPPED))
                        }
                    }
                }

            Log.d(TAG, "Recording object created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start recording", e)
            recording = null
            currentRecordingFile = null
        }
        sensorHandler.start()
        LogToFileHelper.startLogging(this, "SensorLog")
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

        val event = EventEntity(
            timestamp = timestamp,
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
            videoFilePath = currentRecordingFile?.absolutePath,  // 현재 녹화 중인 파일
            extractedVideoPath = null,  // 아직 추출 안 됨
            status = "pending"  // 추출 대기 상태
        )

        // DB에 비동기로 저장 (0.1초 이내)
        // DB에 비동기로 저장 (lifecycleScope 사용)
        lifecycleScope.launch(Dispatchers.IO) {
            eventDao.insert(event)
        }

        Log.d("HybridRecorder", "⚡ 충격 이벤트 마커 저장: $timestamp")

        // 사용자에게 즉시 알림
        //showImpactNotification(timestamp)
    }

    private fun scheduleEventExtraction(videoFile: File) {
        val workRequest = OneTimeWorkRequestBuilder<EventExtractionWorker>()
            .setInputData(
                workDataOf("video_path" to videoFile.absolutePath)
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)  // 배터리 20% 이상일 때만
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
        Log.d(TAG, "📋 이벤트 추출 작업 예약: ${videoFile.name}")
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