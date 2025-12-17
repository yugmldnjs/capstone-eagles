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
import android.speech.tts.TextToSpeech
import android.media.AudioAttributes
import android.os.Bundle
import android.os.SystemClock
class RecordingService : Service(), LifecycleOwner, SensorHandler.EventListener {

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

        private const val EVENT_SCORE_THRESHOLD = 0.7f // 포트홀 판정 신뢰도
        private const val EVENT_NEAR_Y = 0.3f
        // GPS 기반 급정거 감지 파라미터 (속도 단위: km/h)
        private const val GPS_BRAKE_MIN_SPEED_KMH = 5.0f          // 이 속도 이상에서만 급정거 판단
        private const val GPS_BRAKE_DROP_THRESHOLD_KMH = 8.0f     // Δv 가 이 값 이상이면 급정거
        private const val GPS_BRAKE_TIME_WINDOW_MS = 1500L        // 이 시간 안에 일어난 속도 감소만 인정
        private const val FALL_THRESHOLD_SPEED_KMH = 5.0f
        private const val FALL_THRESHOLD_DEG = 190.0f
        private const val EVENT_COOL_DOWN_MS = 15000L

        private const val PERF_LOG_INTERVAL_MS = 1000L

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
    private var lastSpeedKmh: Float? = null
    private var lastSpeedTimestamp: Long = 0L

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

    // ✅ 충격 감지 TTS
    private var impactTts: TextToSpeech? = null

    //fps, 추론속도 측정
    private var frameCount = 0
    private var fpsStartTime = 0L

    private var inferenceCount = 0
    private var totalInferenceTimeMs = 0L



    fun consumeLastPotholeCrop(): Bitmap? {
        val bmp = lastPotholeCrop
        lastPotholeCrop = null
        return bmp
    }

    private fun isPotholeModelEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("use_pothole_model", true)
    }

    private fun isPotholeSoundEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("enable_pothole_tts", true)
    }

    private fun isEventSoundEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("event_detected_tts", true)
    }

    // ✅ 모델이 포트홀을 감지했을 때 짧은 beep- 소리
    private fun playPotholeBeep() {
        // 설정에서 음성 안내가 꺼져 있으면 바로 리턴
        if (!isPotholeSoundEnabled()) return

        val gen = toneGenerator ?: return
        try {
            gen.startTone(ToneGenerator.TONE_PROP_BEEP, 150) // 150ms 정도
        } catch (e: Exception) {
            Log.w(TAG, "포트홀 beep 재생 실패", e)
        }
    }

    // ✅ 충격 감지 시 음성 안내
    private fun speakEventDetected() {
        // 설정에서 음성 안내 꺼져 있으면 재생 안 함 (포트홀 TTS와 같은 스위치 사용)
        if (!isEventSoundEnabled()) return

        val ttsEngine = impactTts ?: return

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)  // 0.0 ~ 1.0
        }

        // TTS는 메인 스레드에서 돌리는 게 안전하니까 handler로 넘김
        mainHandler.post {
            ttsEngine.speak(
                "이벤트가 감지되었습니다.",
                TextToSpeech.QUEUE_ADD,
                params,
                "EVENT_DETECTED"
            )
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

// ✅ 새로 등장한 트랙 → beep 용도로만 사용 (이벤트 판정은 아래에서 따로)
//        val addedIds = currentIds - prevTrackIds
//        if (addedIds.isNotEmpty()) {
//            // 한 프레임에 여러 개 생겨도 "띵-" 한 번이면 충분하다고 보고 1번만 호출
//            playPotholeBeep()
//        }

// ✅ 살아있는 트랙 상태 업데이트 + 근거리 진입 이벤트 체크
        for (t in tracks) {
            val state = trackStates.getOrPut(t.id) {
                PotholeTrackState(
                    firstFrame = frameIndex,
                    lastFrame = frameIndex,
                    maxScore = t.score,
                    lastCy = t.bbox[1],
                    mapped = false
                )
            }

            // 이 트랙에서 아직 이벤트가 안 났고,
            // 이번 프레임에 "처음으로" 화면 하단 근거리 영역에 들어왔으면 이벤트 발생
            //  -> y(=bbox[1])가 EVENT_NEAR_Y 이하(하단 영역)이고, 신뢰도도 기준 이상일 때
            if (!state.mapped &&
                t.score >= EVENT_SCORE_THRESHOLD &&
                t.bbox[1] >= EVENT_NEAR_Y
            ) {
                playPotholeBeep()
                hasNewPotholeEvent = true
                state.mapped = true    // 이 트랙에서는 더 이상 이벤트 안 나가도록 플래그
            }

            // 통계용 상태는 그대로 갱신
            state.lastFrame = frameIndex
            if (t.score > state.maxScore) {
                state.maxScore = t.score
            }
            state.lastCy = t.bbox[1]
        }

// 프레임에서 완전히 사라진 트랙 → 상태 정리
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
                currentSpeed = if(location.speed * 3.6f > 1.0f) location.speed * 3.6f else 0.0f // m/s -> km/h

                val now = System.currentTimeMillis()
                detectGpsSuddenBrake(currentSpeed, now)
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
        toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100)

        // ✅ 충격 감지 TTS 초기화
        impactTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                impactTts?.language = Locale.KOREAN

                val audioAttrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
                impactTts?.setAudioAttributes(audioAttrs)
            } else {
                Log.e(TAG, "Impact TTS 초기화 실패: $status")
            }
        }

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

        // 2) VideoCapture
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        val resValue = prefs.getString("resolution", "720")

        // 해상도별 Quality + Bitrate 설정
        val (targetQuality, targetBitrate) = when (resValue) {
            "1080" -> Quality.FHD to (6 * 1024 * 1024)   // 6 Mbps
            "720"  -> Quality.HD  to (3 * 1024 * 1024)   // 3 Mbps
            "480"  -> Quality.SD  to (1.5 * 1024 * 1024).toInt() // 1.5 Mbps
            else   -> Quality.HD  to (3 * 1024 * 1024)
        }

        Log.d(TAG, "설정된 해상도: $resValue, 비트레이트: ${targetBitrate / 1024 / 1024} Mbps")

        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(
                    targetQuality,
                    FallbackStrategy.lowerQualityOrHigherThan(targetQuality)
                )
            )
            .setTargetVideoEncodingBitRate(targetBitrate)
            .build()

        videoCapture = VideoCapture.withOutput(recorder)


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
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build().also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { image ->

                        // ================= FPS 기준 프레임 카운트 =================
                        frameCount++
                        if (fpsStartTime == 0L) {
                            fpsStartTime = SystemClock.elapsedRealtime()
                        }

                        // ★ 1) 추론 최소 간격 체크 (기존 코드 유지)
                        val nowWall = System.currentTimeMillis()
                        if (nowWall - lastInferenceTime < MIN_INFERENCE_INTERVAL_MS) {
                            image.close()
                            return@setAnalyzer
                        }
                        lastInferenceTime = nowWall

                        // ================= 추론 시간 측정 시작 =================
                        val inferenceStart = SystemClock.elapsedRealtime()

                        try {
                            val detections = detector.detect(image)

                            // ================= 추론 시간 측정 종료 =================
                            val inferenceEnd = SystemClock.elapsedRealtime()
                            val inferenceTime = inferenceEnd - inferenceStart

                            inferenceCount++
                            totalInferenceTimeMs += inferenceTime

                            val (tracks, hasNewPotholeEvent) =
                                updateTrackerAndCheckNewPothole(detections)

                            // ✅ 기존 로직 그대로
                            val bestDetection = detections.maxByOrNull { it.score }
                            if (bestDetection != null) {
                                val crop = detector.cropPotholeBitmap(image, bestDetection)
                                if (crop != null) {
                                    lastPotholeCrop = crop
                                    Log.d(
                                        TAG,
                                        "최근 포트홀 사진 crop 업데이트 (w=${crop.width}, h=${crop.height})"
                                    )
                                }
                            }

                            if (hasNewPotholeEvent) {
                                Log.d(
                                    TAG,
                                    "새 포트홀 확정: hasNewPotholeEvent=true, lastPotholeCrop != null ? ${lastPotholeCrop != null}"
                                )
                            }

                            potholeListener?.let { listener ->
                                mainHandler.post {
                                    listener(tracks, hasNewPotholeEvent)
                                }
                            }

                        } catch (e: Exception) {
                            Log.e(TAG, "Error during pothole detection", e)
                        } finally {

                            // ================= FPS / 평균 추론 로그 =================
                            val nowPerf = SystemClock.elapsedRealtime()
                            val elapsed = nowPerf - fpsStartTime

                            if (elapsed >= PERF_LOG_INTERVAL_MS) {
                                val fps = frameCount * 1000f / elapsed
                                val avgInference =
                                    if (inferenceCount > 0)
                                        totalInferenceTimeMs.toFloat() / inferenceCount
                                    else 0f

                                Log.d(
                                    "PERF",
                                    "FPS=%.1f | Avg Inference=%.2f ms | Frames=%d"
                                        .format(fps, avgInference, inferenceCount)
                                )

                                // 리셋
                                frameCount = 0
                                inferenceCount = 0
                                totalInferenceTimeMs = 0L
                                fpsStartTime = nowPerf
                            }

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
                            // 하이브리드 로거 초기화
                            hybridLogger = HybridSensorLogger(
                                videoFile = currentRecordingFile!!,
                                recordingStartTime = currentRecordingStartTime
                            ).also {
                                Log.d(TAG, "✅ HybridSensorLogger 초기화 완료")
                                Log.d(TAG, "   영상: ${currentRecordingFile!!.name}")
                                Log.d(TAG, "   SRT: ${it.getSrtFilePath()}")
                            }

                            // 🆕 1초 타이머 시작
                            startSrtLoggingTimer()

                            try {
                                val locationRequest = LocationRequest.Builder(
                                    Priority.PRIORITY_HIGH_ACCURACY,
                                    500L // 1초 간격
                                ).apply {
                                    setMinUpdateIntervalMillis(500L)
                                    setMaxUpdateDelayMillis(1000L)
                                    setMinUpdateDistanceMeters(0f)
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
                            location = location,
                            speed = currentSpeed
                        )

                        Log.d(TAG, "✅ SRT 로그 기록 (타이머)")
                    } else {
                        hybridLogger?.logSensorData(
                            location = Location("null"),
                            speed = 0.0f)
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

    // GPS 속도를 이용한 급정거 판단
    private fun detectGpsSuddenBrake(newSpeedKmh: Float, now: Long) {
        val prevSpeed = lastSpeedKmh
        val prevTime = lastSpeedTimestamp

        if (prevSpeed != null && prevTime > 0L) {
            val dt = now - prevTime
            if (dt in 1..GPS_BRAKE_TIME_WINDOW_MS) {
                val speedDrop = prevSpeed - newSpeedKmh   // 양수일 때 감속

                if (prevSpeed >= GPS_BRAKE_MIN_SPEED_KMH &&
                    speedDrop >= GPS_BRAKE_DROP_THRESHOLD_KMH
                ) {
                    Log.d(
                        TAG,
                        "🛑 GPS 급정거 감지: prev=${"%.1f".format(prevSpeed)}," +
                                " now=${"%.1f".format(newSpeedKmh)}," +
                                " drop=${"%.1f".format(speedDrop)} km/h, dt=${dt}ms"
                    )

                    // 기존 센서 이벤트와 동일 경로로 저장 + 30초 쿨다운 적용
                    onEventDetected(currentLocation, newSpeedKmh, "SUDDEN_BRAKE")
                }
            }
        }

        // 마지막 속도/시간 갱신
        lastSpeedKmh = newSpeedKmh
        lastSpeedTimestamp = now
    }


    override fun onFallCandidate(rotation: Float) {
        val speed = currentSpeed
        if (rotation > FALL_THRESHOLD_DEG || speed < FALL_THRESHOLD_SPEED_KMH){
            return onEventDetected(currentLocation, speed,"FALL")
        }
    }

    private fun onEventDetected(location: Location?, speed: Float, eventType: String) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < EVENT_COOL_DOWN_MS) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신

        createAndSaveEvent(timestamp, location, speed, eventType)
    }

    // EventEntity를 생성하고 저장하는 헬퍼 함수 (코드 중복 방지)
    private fun createAndSaveEvent(
        timestamp: Long,
        location: Location?,
        speed: Float,
        eventType: String
    ) {
        Log.d(TAG, "location: ${location?.latitude}, ${location?.longitude}")

        val event = EventEntity(
            timestamp = timestamp,
            recordingStartTimestamp = currentRecordingStartTime,
            type = eventType.lowercase(),
            latitude = location?.latitude,
            longitude = location?.longitude,
            speed = speed,
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

            // 🔊 충격 감지 TTS
            speakEventDetected()
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

        impactTts?.stop()
        impactTts?.shutdown()
        impactTts = null
    }
}