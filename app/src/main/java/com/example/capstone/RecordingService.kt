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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.MediaStore
import android.util.Half.EPSILON
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
import java.lang.ref.WeakReference
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class RecordingService : Service(), LifecycleOwner, SensorEventListener {

    // 1. 콜백 인터페이스 정의
//    interface SensorCallback {
//        fun onSensorDataChanged(accelData: FloatArray,linearAccel: FloatArray)
//    }

    // 2. 콜백을 저장할 변수 추가 (메모리 누수 방지를 위해 WeakReference 사용)
    //private var sensorCallback: WeakReference<SensorCallback>? = null

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
    // 센서 관련
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null  // 가속도 센서
    private var gyroscope: Sensor? = null      // 자이로스코프
    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)
    private val NS2S = 1.0f / 1000000000.0f
    private val deltaRotationVector = FloatArray(4)
    private var timestamp: Float = 0f

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

        // SensorManager 초기화 및 리스너 등록
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

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
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
        LogToFileHelper.startLogging(this, "SensorLog")
    }

    fun stopRecording() {
        Log.d(TAG, "stopRecording() called")
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

    // 센서 변화시
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                // 중력 계산
                gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * it.values[0]
                gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * it.values[1]
                gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * it.values[2]

                // 선형 가속도
                linearAccel[0] = it.values[0] - gravity[0]
                linearAccel[1] = it.values[1] - gravity[1]
                linearAccel[2] = it.values[2] - gravity[2]

                // 총 가속도 크기
                val totalAccel = sqrt(linearAccel[0]*linearAccel[0] + linearAccel[1]*linearAccel[1] + linearAccel[2]*linearAccel[2])

                //sensorCallback?.get()?.onSensorDataChanged(it.values, linearAccel)

                // 로그 출력
                Log.d(TAG, "원본 - X: %.2f, Y: %.2f, Z: %.2f".format(it.values[0], it.values[1], it.values[2]))
                Log.d(
                    TAG, "중력 - X: %.2f, Y: %.2f, Z: %.2f"
                    .format(gravity[0], gravity[1], gravity[2]))
                Log.d(
                    TAG, "순수 가속도 - X: %.2f, Y: %.2f, Z: %.2f"
                    .format(linearAccel[0], linearAccel[1], linearAccel[2]))
                Log.d(TAG, "총 가속도: %.2f".format(totalAccel))
                // 로그파일에 쓰기
                val accelLog = "ACC - X: %.2f, Y: %.2f, Z: %.2f | Total: %.2f".format(
                    linearAccel[0], linearAccel[1], linearAccel[2], totalAccel
                )
                LogToFileHelper.writeLog(accelLog)

                // 충격 감지
                if(totalAccel > 10.0 ){
                    val absLinearAccel = linearAccel.map { abs(it) }
                    val maxIndex = absLinearAccel.indexOf(absLinearAccel.maxOrNull())
                    val minIndex = absLinearAccel.indexOf(absLinearAccel.minOrNull())

                    // 급제동 감지
                    if(maxIndex == 2 && linearAccel[maxIndex] < 0) {
                        Log.d(TAG, "z: %.2f".format(linearAccel[maxIndex]))
                        val crashLog = "crash - X: %.2f, Y: %.2f, Z: %.2f | Total: %.2f".format(
                            linearAccel[0], linearAccel[1], linearAccel[2], totalAccel
                        )
                        LogToFileHelper.writeLog(crashLog)
                    }
                }
                Log.d(TAG, "---")

            } else if(it.sensor.type == Sensor.TYPE_GYROSCOPE) {
                // This timestep's delta rotation to be multiplied by the current rotation
                // after computing it from the gyro sample data.

                val dT = (event.timestamp - timestamp) * NS2S
                // Axis of the rotation sample, not normalized yet.
                var axisX: Float = it.values[0]
                var axisY: Float = it.values[1]
                var axisZ: Float = it.values[2]
                // 각속도 로그 출력 (디버깅용)
                Log.d(
                    TAG,
                    "자이로 - X(Roll): %.2f, Y(Pitch): %.2f, Z(Yaw): %.2f".format(axisX, axisY, axisZ)
                )
                // 로그 파일에 쓰기
                val gyroLog = "GYRO - X: %.2f, Y: %.2f, Z: %.2f".format(axisX, axisY, axisZ)
                LogToFileHelper.writeLog(gyroLog)

                // 넘어짐 감지를 위한 임계값 (실제 테스트를 통해 조절 필요)
                // 일반적으로 자전거가 옆으로 넘어질 때 X축(Roll) 회전이 가장 큽니다.
                val fallThreshold = 2.5f // 예: 2.5 rad/s 이상으로 빠르게 회전하면 낙차로 판단

                // X축(Roll) 각속도의 절대값이 임계값을 초과하는지 확인
                if (abs(axisX) > fallThreshold) {
                    Log.w(TAG, "!!! 낙차 감지 (넘어짐) !!! - X축 회전 속도: %.2f".format(axisX))

                    // TODO: 여기에 낙차 감지 시 수행할 동작을 구현합니다.
                    // 예: 1. 별도의 이벤트 영상으로 저장하는 로직 호출
                    //    2. 서버로 긴급 알림 전송
                    //    3. MainActivity2로 브로드캐스트 전송하여 UI에 표시
                }

            }

        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

//    fun setSensorCallback(callback: SensorCallback?) {
//        sensorCallback = if (callback != null) {
//            WeakReference(callback)
//        } else {
//            null
//        }
//    }

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
        sensorManager.unregisterListener(this)
    }

    companion object {
        private const val TAG = "RecordingService"
        private const val CHANNEL_ID = "recording_channel"
        private const val NOTIFICATION_ID = 1
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"

        const val ACTION_RECORDING_STARTED = "com.example.capstone.RECORDING_STARTED"
        const val ACTION_RECORDING_STOPPED = "com.example.capstone.RECORDING_STOPPED"
        const val ACTION_RECORDING_SAVED = "com.example.capstone.RECORDING_SAVED"

        private const val ALPHA = 0.8f
    }
}