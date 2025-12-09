package com.example.capstone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

class SensorHandler(context: Context, private var listener: EventListener?) : SensorEventListener {

    companion object {
        private const val TAG = "SensorHandler"

        // ✅ 노이즈 필터링 개선
        private const val GRAVITY_ALPHA = 0.9f  // Low-pass filter (높을수록 부드러움)
        private const val ACCEL_NOISE_THRESHOLD = 0.5f  // 노이즈 임계값 (m/s²)

        // ✅ 쿨다운 시간 설정
        private const val COOLDOWN_MS = 2000L  // 2초

        // ✅ Moving Average 필터 윈도우 크기
        private const val MOVING_AVG_WINDOW = 5
        // ✅ 자이로 로그 최소 간격 (너무 많이 찍히는 걸 방지용)
        private const val GYRO_LOG_INTERVAL_MS = 200L   // 0.2초마다 한 번 정도
    }

    // 센서 관리자
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // ✅ 센서 데이터 저장 (Low-pass filter용)
    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)

    // ✅ Moving Average 필터용 버퍼
    private val accelBufferX = ArrayDeque<Float>(MOVING_AVG_WINDOW)
    private val accelBufferY = ArrayDeque<Float>(MOVING_AVG_WINDOW)
    private val accelBufferZ = ArrayDeque<Float>(MOVING_AVG_WINDOW)
    // ✅ 마지막 이벤트 감지 시간
    private var lastImpactTime = 0L

    // ✅ 마지막 자이로 로그 시각
    private var lastGyroLogTime = 0L

    init {
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        if (accelerometer == null) {
            Log.e(TAG, "⚠️ 가속도계를 사용할 수 없습니다")
        }
        if (gyroscope == null) {
            Log.e(TAG, "⚠️ 자이로스코프를 사용할 수 없습니다")
        }
    }

    /**
     * 센서 리스너 등록 및 데이터 수집 시작
     */
    fun start() {
        Log.d(TAG, "센서 리스너 등록 시작")
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    /**
     * 센서 리스너 해제 및 데이터 수집 중단
     */
    fun stop() {
        Log.d(TAG, "센서 리스너 해제")
        sensorManager.unregisterListener(this)
        // 버퍼 초기화
        accelBufferX.clear()
        accelBufferY.clear()
        accelBufferZ.clear()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    processAccelerometer(it)
                }
                Sensor.TYPE_GYROSCOPE -> {
                    processGyroscope(it)
                }
            }
        }
    }

    /**
     * ✅ 가속도계 데이터 처리 (노이즈 필터링 + 급정거/충격 감지)
     */
    private fun processAccelerometer(event: SensorEvent) {

        // 1️⃣ Low-pass filter로 중력 성분 분리
        gravity[0] = GRAVITY_ALPHA * gravity[0] + (1 - GRAVITY_ALPHA) * event.values[0]
        gravity[1] = GRAVITY_ALPHA * gravity[1] + (1 - GRAVITY_ALPHA) * event.values[1]
        gravity[2] = GRAVITY_ALPHA * gravity[2] + (1 - GRAVITY_ALPHA) * event.values[2]

        // 2️⃣ 선형 가속도 계산 (중력 제거)
        val rawLinearX = event.values[0] - gravity[0]
        val rawLinearY = event.values[1] - gravity[1]
        val rawLinearZ = event.values[2] - gravity[2]

        // 3️⃣ Moving Average 필터 적용 (노이즈 제거)
        linearAccel[0] = applyMovingAverage(rawLinearX, accelBufferX)
        linearAccel[1] = applyMovingAverage(rawLinearY, accelBufferY)
        linearAccel[2] = applyMovingAverage(rawLinearZ, accelBufferZ)

        // 4️⃣ 노이즈 임계값 필터링 (작은 떨림 제거)
        if (kotlin.math.abs(linearAccel[0]) < ACCEL_NOISE_THRESHOLD) linearAccel[0] = 0f
        if (kotlin.math.abs(linearAccel[1]) < ACCEL_NOISE_THRESHOLD) linearAccel[1] = 0f
        if (kotlin.math.abs(linearAccel[2]) < ACCEL_NOISE_THRESHOLD) linearAccel[2] = 0f

        // 5️⃣ 수평 방향 가속도 크기 계산 (x, y축 - 진행 방향)
        val horizontalAccel = sqrt(
            linearAccel[0] * linearAccel[0] +
                    linearAccel[1] * linearAccel[1]
        )

        // 6️⃣ 전체 가속도 크기 계산
        val totalAccel = sqrt(
            linearAccel[0] * linearAccel[0] +
                    linearAccel[1] * linearAccel[1] +
                    linearAccel[2] * linearAccel[2]
        )

    }

    /**
     * ✅ Moving Average 필터 적용
     */
    private fun applyMovingAverage(newValue: Float, buffer: ArrayDeque<Float>): Float {
        // 버퍼에 새 값 추가
        buffer.addLast(newValue)

        // 버퍼 크기 제한
        if (buffer.size > MOVING_AVG_WINDOW) {
            buffer.removeFirst()
        }

        // 평균 계산
        return buffer.average().toFloat()
    }

    /**
     * 자이로스코프 데이터 처리
     */
    private fun processGyroscope(event: SensorEvent) {

        val rotationX = Math.toDegrees(event.values[0].toDouble()).toFloat()
        val rotationY = Math.toDegrees(event.values[1].toDouble()).toFloat()
        val rotationZ = Math.toDegrees(event.values[2].toDouble()).toFloat()

        val totalRotation = sqrt(
            rotationX * rotationX +
                    rotationY * rotationY +
                    rotationZ * rotationZ
        )

        // ✅ 자이로 샘플 로그 (낙차 튜닝용)
        val now = System.currentTimeMillis()
        if (now - lastGyroLogTime >= GYRO_LOG_INTERVAL_MS) {
            lastGyroLogTime = now

            LogToFileHelper.writeLog(
                "GYRO, " +
                        "x=${"%.2f".format(rotationX)}°/s, " +
                        "y=${"%.2f".format(rotationY)}°/s, " +
                        "z=${"%.2f".format(rotationZ)}°/s, " +
                        "total=${"%.2f".format(totalRotation)}°/s"
            )
        }


        // 급격한 회전 감지 (낙상 가능성)
        if (totalRotation > 200.0f) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastImpactTime >= COOLDOWN_MS) {
                lastImpactTime = currentTime
                // ✅ 낙상 의심 값 별도 로그
                LogToFileHelper.writeLog(
                    "FALL_CANDIDATE, " +
                            "total=${"%.2f".format(totalRotation)}°/s, " +
                            "x=${"%.2f".format(rotationX)}, " +
                            "y=${"%.2f".format(rotationY)}, " +
                            "z=${"%.2f".format(rotationZ)}"
                )
                listener?.onEventDetected(linearAccel.clone(), floatArrayOf(rotationX, rotationY, rotationZ), "FALL")
                Log.d(TAG, "🤕 낙상 의심! 회전: ${String.format("%.2f", totalRotation)}°/s")
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        when (accuracy) {
            SensorManager.SENSOR_STATUS_UNRELIABLE ->
                Log.w(TAG, "⚠️ 센서 정확도 낮음: ${sensor?.name}")
            SensorManager.SENSOR_STATUS_ACCURACY_LOW ->
                Log.w(TAG, "📊 센서 정확도: 낮음")
            SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ->
                Log.i(TAG, "📊 센서 정확도: 중간")
            SensorManager.SENSOR_STATUS_ACCURACY_HIGH ->
                Log.i(TAG, "📊 센서 정확도: 높음")
        }
    }

    /**
     * ✅ 이벤트 리스너 인터페이스
     */
    interface EventListener {
        fun onEventDetected(linearAccel: FloatArray, rotation: FloatArray, eventType: String)
    }
}