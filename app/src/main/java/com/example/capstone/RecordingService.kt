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
import com.example.capstone.worker.EventExtractionWorker
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
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
import androidx.preference.PreferenceManager

class RecordingService : Service(), LifecycleOwner, SensorHandler.ImpactListener {

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
    private lateinit var sensorHandler: SensorHandler
    private var currentVideoUri: Uri? = null
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

    // 감지 결과 브로드캐스트 간 최소 간격 (ms)
    private var lastDetectionBroadcastTime: Long = 0L

    private fun isPotholeModelEnabled(): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        return prefs.getBoolean("use_pothole_model", true)
    }

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

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        val database = BikiDatabase.getDatabase(this)
        eventDao = database.eventDao()

        // --- SensorHandler 인스턴스 생성 ---
        sensorHandler = SensorHandler(this, this)


        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("카메라 준비 중"))
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED

        // ★ 포트홀 감지 모델 초기화 (설정 기반)
        if (isPotholeModelEnabled()) {
            potholeDetector = PotholeDetector(this)
        } else {
            potholeDetector = null
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
            .format(currentRecordingStartTime)}.mp4"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.DATE_TAKEN, currentRecordingStartTime)
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackboxVideos/Full")
            }
        }

        val outputOptionsBuilder = MediaStoreOutputOptions
            .Builder(contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
        if (location != null) {
            outputOptionsBuilder.setLocation(location)
        }
        // 3. 설정을 다 넣은 뒤에 build()를 호출합니다.
        val mediaStoreOutputOptions = outputOptionsBuilder.build()

        val audioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        )


        Log.d(TAG, "Audio permission granted: ${audioPermission == PackageManager.PERMISSION_GRANTED}")

        try {
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
                            lastImpactTimestamp = 0L
                            updateNotification("녹화 중...")
                            sendBroadcast(Intent(ACTION_RECORDING_STARTED))
                            Log.d(TAG, "녹화 시작 성공!")
                        }
                        is VideoRecordEvent.Finalize -> {
                            if (!recordEvent.hasError()) {
                                currentVideoUri = recordEvent.outputResults.outputUri

                                val msg = "영상 저장 완료"
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

    override fun onImpactDetected(linearAccel: FloatArray, totalAccel: Float) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < 30000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신


        checkLocationPermission(timestamp, linearAccel, null)
    }

    override fun onSuddenBrakeDetected(linearAccel: FloatArray, horizontalAccel: Float) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < 30000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신


        checkLocationPermission(timestamp, linearAccel, null)
    }

    override fun onFallDetected(rotation: FloatArray, totalRotation: Float) {
        val timestamp = System.currentTimeMillis()
        if (timestamp - lastImpactTimestamp < 30000) {
            Log.d(TAG, "쿨다운 시간 내의 중복 충격 감지. 무시합니다.")
            return
        }
        lastImpactTimestamp = timestamp // 마지막 충격 시간 갱신


        checkLocationPermission(timestamp, floatArrayOf(0f, 0f, 0f), rotation)
    }

    private fun checkLocationPermission(timestamp: Long, accelData: FloatArray, gyroData: FloatArray?) {
        // --- ⬇️ 여기가 핵심 수정 부분: 위치 정보를 동기적으로 가져와서 이벤트 생성 ⬇️ ---
        try {
            // 1. 위치 권한을 다시 한번 확인합니다.
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (hasLocationPermission) {
                // 2. 현재 위치를 요청하고, 성공/실패에 따라 EventEntity를 생성합니다.
                fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
                    Log.d(TAG, "충격 감지 시 위치 확보: ${location?.latitude}, ${location?.longitude}")
                    // 위치 정보와 함께 EventEntity를 생성하고 DB에 저장합니다.
                    createAndSaveEvent(timestamp, location, accelData, gyroData)
                }.addOnFailureListener {
                    Log.e(TAG, "충격 감지 시 위치 정보 요청 실패", it)
                    // 위치를 못 찾았더라도 이벤트는 기록되어야 하므로, 위치 정보 없이 생성합니다.
                    createAndSaveEvent(timestamp, null, accelData, gyroData)
                }
            } else {
                Log.w(TAG, "충격 감지 시 위치 권한 없음")
                // 권한이 없으면 위치 정보 없이 생성합니다.
                createAndSaveEvent(timestamp, null, accelData, gyroData)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "충격 감지 시 위치 권한 보안 예외", e)
            createAndSaveEvent(timestamp, null, accelData, gyroData)
        }
        // --- ⬆️ 수정 끝 ⬆️ ---
    }

    // EventEntity를 생성하고 저장하는 헬퍼 함수 (코드 중복 방지)
    private fun createAndSaveEvent(
        timestamp: Long,
        location: Location?,
        accelData: FloatArray,
        gyroData: FloatArray?
    ) {
        Log.d(TAG, "location: ${location?.latitude}, ${location?.longitude}")
        val event = EventEntity(
            timestamp = timestamp,
            recordingStartTimestamp = currentRecordingStartTime,
            type = "impact",
            latitude = location?.latitude,
            longitude = location?.longitude,
            speed = currentSpeed,
            accelerometerX = accelData[0],
            accelerometerY = accelData[1],
            accelerometerZ = accelData[2],
            gyroX = gyroData?.get(0),
            gyroY = gyroData?.get(1),
            gyroZ = gyroData?.get(2),
            videoUri = null,
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
        sensorHandler.stop()
        LogToFileHelper.stopLogging()

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