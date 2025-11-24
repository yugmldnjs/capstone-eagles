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
import androidx.camera.core.ImageAnalysis
import android.util.Size
import com.example.capstone.ml.PotholeDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.UseCase
import com.example.capstone.ml.PotholeDetection
import android.os.Handler
import android.os.Looper


class RecordingService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        const val ACTION_RECORDING_STARTED = "com.example.capstone.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.capstone.RECORDING_STOPPED"
        const val ACTION_RECORDING_SAVED = "com.example.capstone.RECORDING_SAVED"
        // ★ 포트홀 감지 브로드캐스트 액션 추가
        const val ACTION_POTHOLE_DETECTIONS = "com.example.capstone.POTHOLE_DETECTIONS"
    }

    // 메인 스레드로 결과를 보내기 위한 핸들러
    private val mainHandler = Handler(Looper.getMainLooper())

    // 포트홀 감지 결과를 받을 리스너 (액티비티에서 등록)
    private var potholeListener: ((List<PotholeDetection>) -> Unit)? = null
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

    private var imageAnalysis: ImageAnalysis? = null

    // 포트홀 감지용 TFLite 래퍼
    private var potholeDetector: PotholeDetector? = null

    // 분석용 전용 스레드
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 감지 결과 브로드캐스트 간 최소 간격 (ms)
    private var lastDetectionBroadcastTime: Long = 0L

    fun setPotholeListener(listener: ((List<PotholeDetection>) -> Unit)?) {
        potholeListener = listener
    }

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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("카메라 준비 중"))
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // ★ 포트홀 감지 모델 초기화
        potholeDetector = PotholeDetector(this)
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

        // 1) Preview (하나만 생성)
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(mainPreviewView.surfaceProvider)
        currentPreview = preview
        Log.d(TAG, "Single preview created")

        // 2) VideoCapture (기존 코드 유지)
        if (videoCapture == null) {
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            Log.d(TAG, "VideoCapture created")
        } else {
            Log.d(TAG, "VideoCapture already exists")
        }

        // 3) ImageAnalysis (포트홀 감지용)
        val detector = potholeDetector
        if (detector == null) {
            Log.e(TAG, "PotholeDetector is null, skip ImageAnalysis")
        } else {
            imageAnalysis = ImageAnalysis.Builder()
                // YOLO 입력 크기에 맞춤 (320x320)
                .setTargetResolution(Size(320, 320))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { image ->
                        try {
                            val detections = detector.detect(image)

                            // ✅ 1) 리스너로 직접 전달 (UI 업데이트용)
                            potholeListener?.let { listener ->
                                mainHandler.post {
                                    listener(detections)
                                }
                            }

                            // ✅ 2) 그대로 브로드캐스트도 유지 (나중에 필요하면 활용)
                            broadcastPotholeDetections(detections)

                            if (detections.isNotEmpty()) {
                                val maxScore = detections.maxOf { it.score }
                                Log.d(
                                    TAG,
                                    "Pothole detected: count=${detections.size}, topScore=$maxScore"
                                )
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Error during pothole detection", e)
                        } finally {
                            image.close()
                        }
                    }
                }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            Log.d(TAG, "Camera unbound")

            // 4) Preview + VideoCapture (+ ImageAnalysis) 바인딩
            val useCases = mutableListOf<UseCase>(preview, videoCapture!!)

            imageAnalysis?.let { analysis ->
                useCases.add(analysis)
            }

            camera = cameraProvider.bindToLifecycle(
                this,
                cameraSelector,
                *useCases.toTypedArray()
            )

            Log.d(
                TAG,
                "Camera bound successfully (Preview + VideoCapture${if (imageAnalysis != null) " + ImageAnalysis" else ""})"
            )
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

        val name = "Blackbox-${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
            .format(System.currentTimeMillis())}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackboxVideos")
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

            Log.d(TAG, "Recording object created successfully")
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

    private fun broadcastPotholeDetections(detections: List<PotholeDetection>) {
        val now = System.currentTimeMillis()
        // 너무 자주 쏘면 부담되니 200ms 간격으로 제한
        if (now - lastDetectionBroadcastTime < 200L) return
        lastDetectionBroadcastTime = now

        // Intent 생성
        val intent = Intent(ACTION_POTHOLE_DETECTIONS)

        // Parcelable ArrayList로 넣기
        intent.putParcelableArrayListExtra(
            "detections",
            ArrayList<PotholeDetection>(detections)
        )

        // ★ 여기 로그 추가
        Log.d(
            TAG,
            "broadcastPotholeDetections() sending ${detections.size} detections"
        )

        // 브로드캐스트 전송
        sendBroadcast(intent)
    }


    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        recording?.stop()
        cameraProvider?.unbindAll()

        // ★ 분석 리소스 정리
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (_: Exception) { }
        imageAnalysis = null

        potholeDetector?.close()
        potholeDetector = null

        analysisExecutor.shutdown()
    }
}