package com.example.capstone

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.sqrt

class SensorHandler(context: Context, private var listener: ImpactListener?) : SensorEventListener {

    companion object {
        private const val TAG = "SensorHandler"

        // ✅ 노이즈 필터링 개선
        private const val GRAVITY_ALPHA = 0.9f  // Low-pass filter (높을수록 부드러움)
        private const val ACCEL_NOISE_THRESHOLD = 0.5f  // 노이즈 임계값 (m/s²)

        // ✅ 급정거 감지 임계값
        private const val SUDDEN_BRAKE_THRESHOLD = 10.0f  // 급정거 임계값 (m/s²)
        private const val SUDDEN_BRAKE_DURATION = 800L  // 급정거 지속 시간 (ms)

        // ✅ 충격 감지 임계값
        private const val IMPACT_THRESHOLD = 20.0f  // 충격 임계값 (m/s²)

        // ✅ 쿨다운 시간 설정
        private const val COOLDOWN_MS = 2000L  // 2초

        // ✅ Moving Average 필터 윈도우 크기
        private const val MOVING_AVG_WINDOW = 5
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

    // ✅ 급정거 감지용 변수
    private var suddenBrakeStartTime = 0L
    private var isBraking = false

    // ✅ 마지막 이벤트 감지 시간
    private var lastImpactTime = 0L
    private var lastBrakeTime = 0L

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

        // 7️⃣ 급정거 감지 (수평 방향 감속)
        detectSuddenBrake(horizontalAccel)

        // 8️⃣ 충격 감지 (모든 방향 포함)
        detectImpact(totalAccel)
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
     * ✅ 급정거 감지 (수평 방향 감속)
     */
    private fun detectSuddenBrake(horizontalAccel: Float) {
        val currentTime = System.currentTimeMillis()
        var logmsg : String
        if (horizontalAccel > SUDDEN_BRAKE_THRESHOLD) {


            // 급정거 시작
            if (!isBraking) {
                isBraking = true
                suddenBrakeStartTime = currentTime
                logmsg = "🛑 급정거 시작 감지: ${String.format("%.2f", horizontalAccel)} m/s²"
                Log.d(TAG, "🛑 급정거 시작 감지: ${String.format("%.2f", horizontalAccel)} m/s²")
                LogToFileHelper.writeLog("============================================")
                LogToFileHelper.writeLog(logmsg)
            }

            // 급정거 지속 시간 체크
            val duration = currentTime - suddenBrakeStartTime
            if (duration >= SUDDEN_BRAKE_DURATION &&
                currentTime - lastBrakeTime >= COOLDOWN_MS) {

                lastBrakeTime = currentTime
                listener?.onSuddenBrakeDetected(linearAccel.clone(), horizontalAccel)
                logmsg = "🛑 급정거 확정! 지속시간: ${duration}ms, 가속도: ${String.format("%.2f", horizontalAccel)} m/s²"
                Log.d(TAG, "🛑 급정거 확정! 지속시간: ${duration}ms, 가속도: ${String.format("%.2f", horizontalAccel)} m/s²")
                LogToFileHelper.writeLog(logmsg)
            }
        } else {
            // 급정거 종료
            if (isBraking) {
                val duration = currentTime - suddenBrakeStartTime
                logmsg = "🟢 급정거 종료 (지속시간: ${duration}ms)"
                Log.d(TAG, "🟢 급정거 종료 (지속시간: ${duration}ms)")
                LogToFileHelper.writeLog(logmsg)



            }
            isBraking = false
        }
    }

    /**
     * ✅ 충격 감지 (모든 방향)
     */
    private fun detectImpact(totalAccel: Float) {
        if (totalAccel > IMPACT_THRESHOLD) {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastImpactTime >= COOLDOWN_MS) {
                lastImpactTime = currentTime
                listener?.onImpactDetected(linearAccel.clone(), totalAccel)
                Log.d(TAG, "⚡ 충격 감지! 가속도: ${String.format("%.2f", totalAccel)} m/s²")
            } else {
                val remainingTime = COOLDOWN_MS - (currentTime - lastImpactTime)
                Log.d(TAG, "🔇 충격 쿨다운 중... (${remainingTime}ms 남음)")
            }
        }
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

        // 급격한 회전 감지 (낙상 가능성)
        if (totalRotation > 200.0f) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastImpactTime >= COOLDOWN_MS) {
                lastImpactTime = currentTime
                listener?.onFallDetected(floatArrayOf(rotationX, rotationY, rotationZ), totalRotation)
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
     * 리스너 업데이트
     */
    fun setListener(listener: ImpactListener) {
        this.listener = listener
    }

    /**
     * ✅ 이벤트 리스너 인터페이스
     */
    interface ImpactListener {
        /**
         * 충격 감지 (과속방지턱, 도로 요철 등)
         */
        fun onImpactDetected(linearAccel: FloatArray, totalAccel: Float)

        /**
         * 급정거 감지
         */
        fun onSuddenBrakeDetected(linearAccel: FloatArray, horizontalAccel: Float)

        /**
         * 낙상 감지 (급격한 회전)
         */
        fun onFallDetected(rotation: FloatArray, totalRotation: Float)
    }
}