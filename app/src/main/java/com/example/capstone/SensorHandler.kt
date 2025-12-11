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


        // ✅ 쿨다운 시간 설정
        private const val COOLDOWN_MS = 2000L  // 2초

        // ✅ 자이로 로그 최소 간격 (너무 많이 찍히는 걸 방지용)
        private const val GYRO_LOG_INTERVAL_MS = 200L   // 0.2초마다 한 번 정도
        private const val FALL_THRESHOLD = 140.0f
    }

    // 센서 관리자
    private val sensorManager: SensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // ✅ 마지막 이벤트 감지 시간
    private var lastImpactTime = 0L

    // ✅ 마지막 자이로 로그 시각
    private var lastGyroLogTime = 0L

    //timestamp and dt
    var timestamp: Long = 0




    init {
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

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
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_GYROSCOPE -> {
                    val now = System.currentTimeMillis()
                    if (now - lastGyroLogTime >= GYRO_LOG_INTERVAL_MS) {
                        lastGyroLogTime = now
                        processGyroscope(it)
                    }
                }
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

        LogToFileHelper.writeLog(
            "GYRO, " +
                    "x=${"%.2f".format(rotationX)}°/s, " +
                    "y=${"%.2f".format(rotationY)}°/s, " +
                    "z=${"%.2f".format(rotationZ)}°/s, " +
                    "total=${"%.2f".format(totalRotation)}°/s"
        )
        // 급격한 회전 감지 (낙상 가능성)
        if (totalRotation > FALL_THRESHOLD) {
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
                //listener?.onEventDetected(linearAccel.clone(), floatArrayOf(rotationX, rotationY, rotationZ), "FALL")
                listener?.onFallCandidate(totalRotation)
                Log.d(TAG, "🤕 낙차 의심! 회전: ${String.format("%.2f", totalRotation)}°/s")
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
        // fun onEventDetected(linearAccel: FloatArray, rotation: FloatArray, eventType: String)
        fun onFallCandidate(rotation: Float)
    }
}