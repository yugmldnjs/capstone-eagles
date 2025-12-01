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
    private var lastEntryEndTime = 0L

    // 실제 타임스탬프 포맷을 위한 Formatter 추가
    private val timestampFormatter = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA)

    /**
     * 센서 데이터를 SRT 형식으로 기록 (1초 간격)
     */
    @Synchronized
    fun logSensorData(
        location: Location,
        speed: Float, // km/h
    ) {
        Log.d(TAG, "logSensorData called")
        val currentTime = System.currentTimeMillis()
        val relativeTimeMs = currentTime - videoStartTime

        // startTime: 이전 엔트리의 endTime (또는 현재 상대 시간)
        val startTimeMs = if (sequenceNumber == 1) {
            relativeTimeMs
        } else {
            lastEntryEndTime
        }

        // endTime: startTime + 1초
        val endTimeMs = relativeTimeMs + 1000L

        // SRT 시간 형식으로 변환
        val startTimeFormatted = formatSrtTime(startTimeMs)
        val endTimeFormatted = formatSrtTime(endTimeMs)
        val currentTimestamp = timestampFormatter.format(java.util.Date(currentTime))

        srtBuilder.append("$sequenceNumber\n")
        srtBuilder.append("$startTimeFormatted --> $endTimeFormatted\n")
        srtBuilder.append("$currentTimestamp    ")
        srtBuilder.append("${String.format("%.1f", speed)} km/h\n")
        srtBuilder.append("${formatCoordinate(location.latitude)}, ${formatCoordinate(location.longitude)}\n")
        srtBuilder.append("\n")

        Log.d(TAG, "✅ SRT 엔트리 추가: #$sequenceNumber at ${relativeTimeMs}ms")

        sequenceNumber++
        lastEntryEndTime = endTimeMs
    }

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
        lastEntryEndTime = 0L
        Log.d(TAG, "✅ SrtSensorLogger 초기화")
    }

    companion object {
        private const val TAG = "SrtSensorLogger"
    }
}