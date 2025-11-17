package com.example.capstone


import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/*SensorHandler에서 발생한 충격 이벤트를 외부로 전달하기 위한 콜백 인터페이스*/
fun interface ImpactListener {
    fun onImpactDetected(accelData: FloatArray, gyroData: FloatArray?)
}
class SensorHandler(context: Context, private var listener: ImpactListener?) : SensorEventListener {

    companion object {
        private const val TAG = "SensorHandler"
        private const val ALPHA = 0.8f
    }

    // 센서 관리자
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // 센서 데이터 저장 변수
    private val gravity = FloatArray(3)
    private val linearAccel = FloatArray(3)

    init {
        // 클래스 생성 시 센서 초기화
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
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

    /**센서 리스너 해제 및 데이터 수집 중단*/
    fun stop() {
        Log.d(TAG, "센서 리스너 해제")
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    if (processAccelerometer(it)) {
                        // --- 가속도계 값(linearAccel)과 함께 콜백 호출 ---
                        // 자이로스코프 값은 아직 없으므로 null 전달
                        listener?.onImpactDetected(linearAccel.clone(), null)
                    }
                }
                Sensor.TYPE_GYROSCOPE -> {
                    if (processGyroscope(it)) {
                        // --- 자이로스코프 값(event.values)과 함께 콜백 호출 ---
                        // 가속도계 값은 직접적인 원인이 아니므로 null 전달
                        listener?.onImpactDetected(floatArrayOf(0f, 0f, 0f), it.values.clone())
                    }
                }
                else -> {} // 다른 종류의 센서는 처리하지 않음
            }
        }

    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // 외부에서 리스너를 제거 가능
    fun releaseListener() {
        this.listener = null
    }
    // 가속도 변화
    private fun processAccelerometer(event: SensorEvent) : Boolean{
        // 중력 필터링
        gravity[0] = ALPHA * gravity[0] + (1 - ALPHA) * event.values[0]
        gravity[1] = ALPHA * gravity[1] + (1 - ALPHA) * event.values[1]
        gravity[2] = ALPHA * gravity[2] + (1 - ALPHA) * event.values[2]

        // 선형 가속도 계산
        linearAccel[0] = event.values[0] - gravity[0]
        linearAccel[1] = event.values[1] - gravity[1]
        linearAccel[2] = event.values[2] - gravity[2]

        val totalAccel = sqrt(linearAccel[0] * linearAccel[0] + linearAccel[1] * linearAccel[1] + linearAccel[2] * linearAccel[2])

        val accelLog = "ACC - X: %.2f, Y: %.2f, Z: %.2f | Total: %.2f".format(
            linearAccel[0], linearAccel[1], linearAccel[2], totalAccel
        )
        //LogToFileHelper.writeLog(accelLog)
        var crash = false

        // 급제동/충격 감지
        if (totalAccel > 10.0) {
            val absLinearAccel = linearAccel.map { abs(it) }
            val maxIndex = absLinearAccel.indexOf(absLinearAccel.maxOrNull())

            if (maxIndex == 2 && linearAccel[maxIndex] < 0) { // Z축 음수방향(차량 전방) 충격
                crash = true
                val crashLog = "!!! 급제동 또는 전방 충격 감지 !!! Z: %.2f".format(linearAccel[maxIndex])
                Log.w(TAG, crashLog)
                //LogToFileHelper.writeLog(crashLog)

                return crash
            }
        }
        return crash
    }

    // 자이로스코프 변화
    private fun processGyroscope(event: SensorEvent) : Boolean {
        var fallen = false
        val axisX = event.values[0]
        val axisY = event.values[1]
        val axisZ = event.values[2]

        val gyroLog = "GYRO - X: %.2f, Y: %.2f, Z: %.2f".format(axisX, axisY, axisZ)
        //LogToFileHelper.writeLog(gyroLog)

        // 낙차 감지
        val fallThreshold = 2.5f
        if (abs(axisX) > fallThreshold) {
            fallen = true
            val fallLog = "!!! 낙차 감지 (넘어짐) !!! - X축 회전 속도: %.2f".format(axisX)
            Log.w(TAG, fallLog)
            //LogToFileHelper.writeLog(fallLog)

            return fallen
        }
        return fallen
    }
}