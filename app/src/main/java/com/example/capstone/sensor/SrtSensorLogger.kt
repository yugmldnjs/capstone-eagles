package com.example.capstone.sensor

import android.location.Location
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.text.format

/**
 * SRT 형식으로 센서 데이터를 실시간 기록하는 로거
 */
class SrtSensorLogger(private val videoStartTime: Long) {
    private val srtBuilder = StringBuilder()
    private var sequenceNumber = 1
//    private var lastLogTime = 0L
    private var lastEntryEndTime: Long? = null
    private val logInterval = 1000L // 1초 간격으로 기록

    // 실제 타임스탬프 포맷을 위한 Formatter 추가
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    /**
     * 센서 데이터를 SRT 형식으로 기록 (1초 간격)
     */
    @Synchronized
    fun logSensorData(
        location: Location,
        speed: Float, // km/h
        accelerometer: FloatArray,
        gyroscope: FloatArray
    ) {
        Log.d(TAG, "logSensorData called")
        val currentTime = System.currentTimeMillis()
        val relativeTimeMs = currentTime - videoStartTime
        // 첫 로그가 아니라면, 마지막 기록 시간으로부터 1초가 지났는지 확인
        val lastEndTime = lastEntryEndTime
        if (lastEndTime != null && relativeTimeMs < lastEndTime) {
            return
        }

//        val startTime = formatSrtTime(relativeTimeMs)
//        val endTime = formatSrtTime(relativeTimeMs + logInterval)
//        val currentTimestamp = timestampFormatter.format(java.util.Date(currentTime))
        // 시작 시간: 이전 자막의 종료 시간. 만약 첫 자막이라면 현재의 상대 시간.
        val startTimeMs = lastEndTime ?: relativeTimeMs
        // 종료 시간: 현재의 상대 시간 + 1초 (다음 로그가 기록될 예상 시간)
        val endTimeMs = relativeTimeMs + logInterval

        // SRT 시간 형식으로 변환
        val startTimeFormatted = formatSrtTime(startTimeMs)
        val endTimeFormatted = formatSrtTime(endTimeMs)

        // 실제 시간 포맷
        val currentTimestamp = timestampFormatter.format(java.util.Date(currentTime))
        // 이미 기록된 내용이 있다면, 이전 자막의 종료 시간을 현재 자막의 시작 시간으로 덮어씁니다.
        if (sequenceNumber > 1) {
            // 마지막 줄바꿈(\n\n)을 지우고 이전 항목의 종료 시간을 수정
            val lastEntryIndex = srtBuilder.lastIndexOf("$sequenceNumber\n")
            if (lastEntryIndex != -1) {
                val previousEntryHeaderEnd = srtBuilder.indexOf("\n", srtBuilder.lastIndexOf(" --> ", lastEntryIndex) + 5)
                if (previousEntryHeaderEnd != -1) {
                    srtBuilder.replace(previousEntryHeaderEnd - 13, previousEntryHeaderEnd, startTimeFormatted)
                }
            }
        }

        srtBuilder.append("$sequenceNumber\n")
        srtBuilder.append("$startTimeFormatted --> $endTimeFormatted\n")
        srtBuilder.append("$currentTimestamp\n")
        srtBuilder.append("${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}\n")
        srtBuilder.append("${String.format("%.1f", speed)} km/h | ")
        srtBuilder.append("Bearing: ${String.format("%.0f", location.bearing)}°\n")
        srtBuilder.append("Accel: ${formatSensor(accelerometer)} | ")
        srtBuilder.append("Gyro: ${formatSensor(gyroscope)}\n")
        srtBuilder.append("\n")

        sequenceNumber++
        lastEntryEndTime = endTimeMs
    }

    /**
     * 이벤트 발생 시 SRT에 마커 추가
     */
//    @Synchronized
//    fun logEvent(
//        eventType: String,
//        relativeTimeMs: Long,
//        triggerValue: Float,
//        details: String = ""
//    ) {
//        val startTime = formatSrtTime(relativeTimeMs)
//        val endTime = formatSrtTime(relativeTimeMs + 2000) // 이벤트는 2초간 표시
//
//        val eventEmoji = when (eventType.uppercase()) {
//            "IMPACT" -> "💥"
//            "SUDDEN_BRAKE", "BRAKE" -> "🛑"
//            "FALL" -> "⚠️"
//            else -> "⚡"
//        }
//
//        srtBuilder.append("$sequenceNumber\n")
//        srtBuilder.append("$startTime --> $endTime\n")
//        srtBuilder.append("$eventEmoji $eventType DETECTED $eventEmoji\n")
//        srtBuilder.append("Trigger Value: ${String.format("%.2f", triggerValue)}\n")
//        if (details.isNotEmpty()) {
//            srtBuilder.append("$details\n")
//        }
//        srtBuilder.append("\n")
//
//        sequenceNumber++
//    }

    /**
     * 밀리초를 SRT 시간 형식으로 변환 (HH:MM:SS,mmm)
     */
    private fun formatSrtTime(ms: Long): String {
        val hours = ms / 3600000
        val minutes = (ms % 3600000) / 60000
        val seconds = (ms % 60000) / 1000
        val millis = ms % 1000
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, millis)
    }

    /**
     * 좌표를 6자리 소수점으로 포맷
     */
    private fun formatCoordinate(coord: Double): String {
        return String.format("%.6f", coord)
    }

    /**
     * 센서 값 배열을 문자열로 포맷
     */
    private fun formatSensor(values: FloatArray): String {
        return values.joinToString(",") { String.format("%.2f", it) }
    }

    /**
     * SRT 파일로 저장
     */
    fun save(file: File) {
        try {
            file.writeText(srtBuilder.toString())
            Log.d(TAG, "✅ SRT 파일 저장 완료: ${file.path}")
            Log.d(TAG, "   총 ${sequenceNumber - 1}개 엔트리")
        } catch (e: Exception) {
            Log.e(TAG, "❌ SRT 파일 저장 실패", e)
        }
    }

    /**
     * 로거 초기화
     */
    fun clear() {
        srtBuilder.clear()
        sequenceNumber = 1
        lastEntryEndTime = null
    }

    companion object {
        private const val TAG = "SrtSensorLogger"
    }
}