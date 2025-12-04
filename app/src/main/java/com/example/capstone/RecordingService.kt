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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.widget.Toast
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
import com.example.capstone.sensor.HybridSensorLogger
import com.example.capstone.worker.EventExtractionWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import androidx.camera.core.ImageAnalysis
import android.util.Size
import com.example.capstone.ml.PotholeDetector
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.camera.core.UseCase
import com.example.capstone.ml.PotholeDetection
import androidx.preference.PreferenceManager
import com.example.capstone.ml.IOUTracker
import com.example.capstone.ml.BoundingBox
import com.example.capstone.ml.Track
import android.media.AudioManager
import android.media.ToneGenerator
import android.graphics.Bitmap
import java.util.Objects.nonNull

class RecordingService : Service(), LifecycleOwner, SensorHandler.ImpactListener {

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"

        const val ACTION_RECORDING_STARTED = "com.example.capstone.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.capstone.RECORDING_STOPPED"
        const val ACTION_RECORDING_SAVED = "com.example.capstone.RECORDING_SAVED"

        // ★ TFLite 추론 간 최소 간격 (ms) – 필요시 조절
        private const val MIN_INFERENCE_INTERVAL_MS = 0L

    }

    // 메인 스레드로 결과를 보내기 위한 핸들러
    private val mainHandler = Handler(Looper.getMainLooper())

    // 포트홀 감지 결과를 받을 리스너 (액티비티에서 등록)
    // 2번째 인자: 이번 프레임에서 "새 포트홀 확정 이벤트"가 있었는지 여부
    private var potholeListener: ((List<Track>, Boolean) -> Unit)? = null

    // IOU 기반 추적기 + 트랙 상태 관리
    private var iouTracker: IOUTracker? = null

    private data class PotholeTrackState(
        var firstFrame: Int,
        var lastFrame: Int,
        var maxScore: Float,
        var lastCy: Float,
        var mapped: Boolean = false
    )

    private val trackStates = mutableMapOf<Int, PotholeTrackState>()
    private var prevTrackIds: Set<Int> = emptySet()
    private var frameIndex: Int = 0

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
    private var currentRecordingFile: File? = null
    private var currentRecordingStartTime: Long = 0
    private lateinit var fusedLocationClient: FusedLocationProviderClient  // 위치 정보 가져오기
    var currentLocation: Location? = null
    var currentSpeed: Float = 0f
    private var lastImpactTimestamp: Long = 0
    private lateinit var eventDao: EventDao

    private var imageAnalysis: ImageAnalysis? = null

    // 포트홀 감지용 TFLite 래퍼
    private var potholeDetector: PotholeDetector? = null

    // 분석용 전용 스레드
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // ★ TFLite 추론 간 최소 간격 제어용
    private var lastInferenceTime: Long = 0L

    // ✅ 포트홀 감지 알림음 재생용
    private var toneGenerator: ToneGenerator? = null

    // ✅ 새 포트홀 발생 시 잘라낸 사진 임시 보관
    @Volatile
    private var lastPotholeCrop: Bitmap? = null

    fun consumeLastPotholeCrop(): Bitmap? {
        val bmp = lastPotholeCrop
        lastPotholeCrop = null
        return bmp
    }

    private fun isPotholeModelEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("use_pothole_model", true)
    }

    // ✅ 모델이 포트홀을 감지했을 때 짧은 띵- 소리
    private fun playPotholeBeep() {
        val gen = toneGenerator ?: return
        try {
            gen.startTone(ToneGenerator.TONE_PROP_BEEP, 150) // 150ms 정도
        } catch (e: Exception) {
            Log.w(TAG, "포트홀 beep 재생 실패", e)
        }
    }

    private fun resetTrackerState() {
        iouTracker?.reset()
        trackStates.clear()
        prevTrackIds = emptySet()
        frameIndex = 0
    }

    fun updateTrackerAndCheckNewPothole(
        detections: List<PotholeDetection>
    ): Pair<List<Track>, Boolean> {
        val tracker = iouTracker ?: return emptyList<Track>() to false
        frameIndex++

        // PotholeDetection -> BoundingBox 변환
        val boxes = detections.map {
            BoundingBox(
                cx = it.cx,
                cy = it.cy,
                w = it.w,
                h = it.h,
                cls = 0,
                cnf = it.score,
                clsName = "pothole"
            )
        }

        val tracks = tracker.update(boxes)
        val currentIds = tracks.map { it.id }.toSet()

        var hasNewPotholeEvent = false

        // ✅ 이번 프레임에서 새로 등장한 트랙 ID들
        val addedIds = currentIds - prevTrackIds
        if (addedIds.isNotEmpty()) {
            // 한 프레임에 여러 개 생겨도 "띵-" 한 번이면 충분하다고 보고 1번만 호출
            playPotholeBeep()

            // 새로 생긴 트랙들 중에서, 점수/위치 조건을 만족하면
            // "포트홀을 처음 인식한 순간"으로 보고 이벤트 발생
            val bestNewTrack = tracks
                .filter { it.id in addedIds }
                .maxByOrNull { it.score }

            if (bestNewTrack != null &&
                bestNewTrack.score >= 0.6f &&   // 신뢰도 조건
                bestNewTrack.bbox[1] >= 0.4f    // 화면 아래쪽(0.4 이상)에서만
            ) {
                hasNewPotholeEvent = true
            }
        }

        // 살아있는 트랙 상태 업데이트 (통계용으로 그대로 유지)
        for (t in tracks) {
            val state = trackStates.getOrPut(t.id) {
                PotholeTrackState(
                    firstFrame = frameIndex,
                    lastFrame = frameIndex,
                    maxScore = t.score,
                    lastCy = t.bbox[1]
                )
            }
            state.lastFrame = frameIndex
            if (t.score > state.maxScore) {
                state.maxScore = t.score
            }
            state.lastCy = t.bbox[1]
        }

        // 프레임에서 완전히 사라진 트랙 → 상태만 정리 (이제는 이벤트 발생 X)
        val removedIds = prevTrackIds - currentIds
        for (id in removedIds) {
            trackStates.remove(id)
        }

        prevTrackIds = currentIds

        return tracks to hasNewPotholeEvent
    }

    fun setPotholeListener(listener: ((List<Track>, Boolean) -> Unit)?) {
        potholeListener = listener
    }

    // 하이브리드 센서 로거
    private var hybridLogger: HybridSensorLogger? = null

    // 1초 타이머 추가
    private val srtLoggingHandler = Handler(Looper.getMainLooper())
    private var srtLoggingRunnable: Runnable? = null

    // 위치 업데이트 콜백
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                currentLocation = location
                currentSpeed = location.speed * 3.6f // m/s -> km/h

                Log.d(TAG, "📍 위치 업데이트: ${location.latitude}, ${location.longitude}")
            }
        }
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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val database = BikiDatabase.getDatabase(this)
        eventDao = database.eventDao()

        // --- SensorHandler 인스턴스 생성 ---
        sensorHandler = SensorHandler(this, this)

        // ✅ 포트홀 감지 알림음 초기화
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("카메라 준비 중"))
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // ★ 포트홀 감지 모델 / 추적기 초기화 (설정 기반)
        if (isPotholeModelEnabled()) {
            potholeDetector = PotholeDetector(this)
            // 추적을 좀 더 느슨하게
            iouTracker = IOUTracker(
                maxLost = 8,          // 감지가 몇 프레임 끊겨도 트랙 유지
                iouThreshold = 0.2f,  // IoU 기준도 살짝 완화
                minDetectionConfidence = 0.3f,
                maxDetectionConfidence = 0.9f // 지금은 안 쓰지만 자리 유지
            )
            resetTrackerState()
        } else {
            potholeDetector = null
            iouTracker = null
            resetTrackerState()
            Log.d(TAG, "포트홀 모델 비활성화 상태 – 감지 로직 사용 안 함")
        }
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
        if (detector == null || !isPotholeModelEnabled()) {
            // 모델 OFF → 분석 use case 제거
            imageAnalysis = null
            Log.e(TAG, "PotholeDetector is null, skip ImageAnalysis")
        } else {
            imageAnalysis = ImageAnalysis.Builder()
                // YOLO 입력 크기에 맞춤 (320x320)
                .setTargetResolution(Size(320, 320))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { image ->

                        // ★ 1) 추론 최소 간격 체크
                        val now = System.currentTimeMillis()
                        if (now - lastInferenceTime < MIN_INFERENCE_INTERVAL_MS) {
                            image.close()              // 반드시 닫아줘야 함
                            return@setAnalyzer
                        }
                        lastInferenceTime = now

                        try {
                            val detections = detector.detect(image)

                            val (tracks, hasNewPotholeEvent) = updateTrackerAndCheckNewPothole(
                                detections
                            )

                            // ✅ 1) 이번 프레임에 detection 이 하나라도 있으면, 일단 "최근 포트홀 사진"으로 저장
                            val bestDetection = detections.maxByOrNull { it.score }
                            if (bestDetection != null) {
                                val crop = detector.cropPotholeBitmap(image, bestDetection)
                                if (crop != null) {
                                    lastPotholeCrop = crop
                                    Log.d(TAG, "최근 포트홀 사진 crop 업데이트 (w=${crop.width}, h=${crop.height})")
                                }
                            }

                            // ✅ 2) hasNewPotholeEvent == true 인 프레임에서는
                            // 위에서 저장해둔 lastPotholeCrop 을 Activity 쪽에서 consumeLastPotholeCrop() 으로 꺼내서 사용
                            if (hasNewPotholeEvent) {
                                Log.d(TAG, "새 포트홀 확정: hasNewPotholeEvent=true, lastPotholeCrop != null ? ${lastPotholeCrop != null}")
                            }

                            // ✅ 1) 리스너로 전달 (UI 업데이트 + 맵 핀 이벤트)
                            potholeListener?.let { listener ->
                                mainHandler.post {
                                    listener(tracks, hasNewPotholeEvent)
                                }
                            }

                            if (detections.isNotEmpty()) {
                                val maxScore = detections.maxOf { it.score }
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

        currentRecordingStartTime = System.currentTimeMillis()

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
                        currentLocation = location
                        currentSpeed = location.speed * 3.6f // m/s -> km/h

                        // 위치를 찾았으면 위치 정보를 포함해서 녹화 시작
                        startRecordingInternal(videoCapture)
                    } else {
                        Log.w(TAG, "위치 정보 null (GPS 미수신 등)")
                        // 위치를 못 찾았으면 그냥 녹화 시작
                        startRecordingInternal(videoCapture)
                    }
                }.addOnFailureListener {
                    Log.e(TAG, "위치 정보 요청 실패", it)
                    startRecordingInternal(videoCapture)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "위치 권한 보안 예외", e)
                startRecordingInternal(videoCapture)
            }
        } else {
            // 3. 권한이 없으면 바로 녹화 시작 (위치 없음)
            Log.w(TAG, "위치 권한 없음")
            startRecordingInternal(videoCapture)
        }
    }

    // 실제 녹화를 수행하는 내부 함수
    private fun startRecordingInternal(videoCapture: VideoCapture<Recorder>) {
        Log.d(TAG, "startRecordingInternal - Location included: ${currentLocation != null}")
        val name = "Bik-i_${
            SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
                .format(currentRecordingStartTime)
        }.mp4"

        currentRecordingFile = File(
            getExternalFilesDir("Recordings"),  // 또는 getExternalFilesDir(null)
            name
        ).apply {
            parentFile?.mkdirs()
        }

        // 하이브리드 로거 초기화
        hybridLogger = HybridSensorLogger(
            videoFile = currentRecordingFile!!,
            recordingStartTime = currentRecordingStartTime
        ).also {
            Log.d(TAG, "✅ HybridSensorLogger 초기화 완료")
            Log.d(TAG, "   영상: ${currentRecordingFile!!.name}")
            Log.d(TAG, "   SRT: ${it.getSrtFilePath()}")
            Log.d(TAG, "   JSON: ${it.getJsonFilePath()}")
        }

        // 🆕 1초 타이머 시작
        startSrtLoggingTimer()

        try {
            val locationRequest = LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                1000L // 1초 간격
            ).apply {
                setMinUpdateIntervalMillis(500L)
                setMaxUpdateDelayMillis(2000L)
            }.build()

            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "📍 위치 업데이트 시작 (currentLocation 업데이트용)")
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 권한 없음", e)
        }

        val fileOutputOptions = FileOutputOptions.Builder(currentRecordingFile!!)
            .apply {
                if (currentLocation != null) {
                    setLocation(currentLocation)
                }
            }
            .build()

        val audioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )


        Log.d(
            TAG,
            "Audio permission granted: ${audioPermission == PackageManager.PERMISSION_GRANTED}"
        )

        try {
            val pendingRecording = videoCapture.output
                .prepareRecording(this, fileOutputOptions)

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
                            lastImpactTimestamp = 0L
                            updateNotification("녹화 중...")
                            sendBroadcast(Intent(ACTION_RECORDING_STARTED))
                            Log.d(TAG, "녹화 시작 성공!")
                        }

                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                val finalFile = currentRecordingFile

                                val msg = "영상 저장 완료"
                                Log.d(TAG, msg)
                                sendBroadcast(Intent(ACTION_RECORDING_SAVED).apply {
                                    putExtra("message", msg)
                                })
                                lifecycleScope.launch(Dispatchers.IO) {
                                    hybridLogger?.finalize(eventDao)
                                }
                                // WorkManager 예약
                                finalFile?.let { scheduleEventExtraction(it.absolutePath) }

                            } else {
                                Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                            }
                            recording = null
                            currentRecordingStartTime = 0
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
            currentRecordingStartTime = 0
            currentRecordingFile = null
        }
        sensorHandler.start()
        LogToFileHelper.startLogging(this, "SensorLog")
    }

    /**
     * 🆕 SRT 로깅 타이머 시작 (1초 간격 강제)
     */
    private fun startSrtLoggingTimer() {

        srtLoggingRunnable = object : Runnable {
            override fun run() {
                // 녹화 중이고 로거가 있으면
                if (recording != null && hybridLogger != null) {
                    val location = currentLocation

                    if (location != null) {
                        // 센서 데이터 기록
                        hybridLogger?.logSensorData(
                            context = this@RecordingService,
                            location = location,
                            speed = currentSpeed,
//                            accelerometer = currentAccelerometer.clone(),
//                            gyroscope = currentGyroscope.clone()
                        )

                        Log.d(TAG, "✅ SRT 로그 기록 (타이머)")
                    } else {
                        Log.w(TAG, "⚠️ 위치 정보 없음 (GPS 대기 중)")
                    }
                }

                // 1초 후 다시 실행
                srtLoggingHandler.postDelayed(this, 1000L)
            }
        }

        // 타이머 시작 (즉시 시작)
        srtLoggingHandler.post(srtLoggingRunnable!!)

        Log.d(TAG, "⏰ SRT 로깅 타이머 시작 (1초 간격)")
    }

    /**
     * 🆕 SRT 로깅 타이머 중지
     */
    private fun stopSrtLoggingTimer() {
        srtLoggingRunnable?.let {
            srtLoggingHandler.removeCallbacks(it)
            srtLoggingRunnable = null
        }
        Log.d(TAG, "⏰ SRT 로깅 타이머 중지")
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording() called")

        val currentRecording = recording
        if (currentRecording == null) {
            Log.w(TAG, "No active recording to stop")
            return
        }

        sensorHandler.stop()
        LogToFileHelper.stopLogging()

        // 🆕 타이머 중지
        stopSrtLoggingTimer()

        // 위치 업데이트 중지
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d(TAG, "📍 위치 업데이트 중지")
        } catch (e: SecurityException) {
            Log.e(TAG, "위치 업데이트 중지 실패", e)
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

    override fun onImpactDetected(linearAccel: FloatArray, totalAccel: Float) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < 30000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신

        createAndSaveEvent(timestamp, currentLocation, linearAccel, null, "IMPACT", totalAccel)
    }

    override fun onSuddenBrakeDetected(linearAccel: FloatArray, horizontalAccel: Float) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < 30000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신

        createAndSaveEvent(
            timestamp,
            currentLocation,
            linearAccel,
            null,
            "SUDDEN_BRAKE",
            horizontalAccel
        )
    }

    override fun onFallDetected(rotation: FloatArray, totalRotation: Float) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < 30000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신

        createAndSaveEvent(
            timestamp,
            currentLocation,
            floatArrayOf(0f, 0f, 0f),
            null,
            "FALL",
            totalRotation
        )
    }


    // EventEntity를 생성하고 저장하는 헬퍼 함수 (코드 중복 방지)
    private fun createAndSaveEvent(
        timestamp: Long,
        location: Location?,
        accelData: FloatArray,
        gyroData: FloatArray?,
        eventType: String,
        triggerValue: Float
    ) {
        Log.d(TAG, "location: ${location?.latitude}, ${location?.longitude}")

        val event = EventEntity(
            timestamp = timestamp,
            recordingStartTimestamp = currentRecordingStartTime,
            type = eventType.lowercase(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            speed = currentSpeed,
            accelerometerX = accelData[0],
            accelerometerY = accelData[1],
            accelerometerZ = accelData[2],
            gyroX = gyroData?.get(0),
            gyroY = gyroData?.get(1),
            gyroZ = gyroData?.get(2),
            videoFilePath = currentRecordingFile?.absolutePath,
            extractedVideoPath = null,
            status = "pending"
        )

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                eventDao.insert(event)
                Log.d(TAG, "✅ DB 저장 성공! (위치 포함: ${location != null})")
            } catch (e: Exception) {
                Log.e(TAG, "DB 저장 중 오류 발생", e)
            }
        }

        GlobalScope.launch(Dispatchers.Main) {
            Toast.makeText(applicationContext, "충격이 감지되었습니다.", Toast.LENGTH_SHORT).show()
        }
        Log.d(TAG, "⚡ 충격 이벤트 마커 저장 로직 완료: $timestamp")
    }

    private fun scheduleEventExtraction(filePath: String) {
        val workRequest = OneTimeWorkRequestBuilder<EventExtractionWorker>()
            .setInputData(
                workDataOf("video_path" to filePath)
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)  // 배터리 20% 이상일 때만
                    .build()
            )
            .build()

        WorkManager.getInstance(this).enqueue(workRequest)
        Log.d(TAG, "📋 이벤트 추출 작업 예약: $filePath")
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopSrtLoggingTimer()
        recording?.stop()
        cameraProvider?.unbindAll()
        sensorHandler.stop()
        fusedLocationClient.removeLocationUpdates(locationCallback)
        LogToFileHelper.stopLogging()

        // ★ 분석 리소스 정리
        try {
            imageAnalysis?.clearAnalyzer()
        } catch (_: Exception) {
        }
        imageAnalysis = null
        potholeDetector?.close()
        potholeDetector = null
        analysisExecutor.shutdown()

        // ✅ 알림음 리소스 정리
        toneGenerator?.release()
        toneGenerator = null
    }
}