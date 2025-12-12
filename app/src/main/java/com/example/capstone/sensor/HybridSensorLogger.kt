package com.example.capstone.sensor

import android.location.Location
import android.util.Log
import com.example.capstone.database.EventDao
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * - SRT: 실시간 센서 데이터 (1초 간격)
 */
class HybridSensorLogger(
    private val videoFile: File,
    private val recordingStartTime: Long
) {
    private val srtLogger = SrtSensorLogger(recordingStartTime)

    private val srtFile: File
        get() = File(videoFile.parent, videoFile.nameWithoutExtension + ".srt")

    /**
     * 실시간 센서 데이터 기록 (SRT용, 1초 간격)
     */
    fun logSensorData(
        location: Location,
        speed: Float, // km/h

    ) {
        srtLogger.logSensorData(location, speed)
    }

    /**
     * 녹화 종료 시: SRT 저장
     */
    suspend fun finalize(eventDao: EventDao) {
        try {
            // 1. SRT 파일 저장
            srtLogger.save(srtFile)

            // 2. DB에서 이 녹화 세션의 이벤트들 조회
            val events = eventDao.getEventsByRecordingStartTime(recordingStartTime)

            Log.i(TAG, "✅ 하이브리드 로그 저장 완료:")
            Log.i(TAG, "   📄 SRT: ${srtFile.name}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 하이브리드 로그 저장 실패", e)
        }
    }


    /**
     * 로거 초기화
     */
    fun clear() {
        srtLogger.clear()
    }

    /**
     * SRT 파일 경로 반환
     */
    fun getSrtFilePath(): String = srtFile.absolutePath

    companion object {
        private const val TAG = "HybridSensorLogger"
    }
}