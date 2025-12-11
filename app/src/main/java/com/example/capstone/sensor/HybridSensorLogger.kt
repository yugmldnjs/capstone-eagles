package com.example.capstone.sensor

import android.location.Location
import android.util.Log
import com.example.capstone.database.EventDao
import com.example.capstone.database.EventEntity
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import java.io.File

/**
 * SRT + JSON 하이브리드 센서 로깅 시스템
 * - SRT: 실시간 센서 데이터 (1초 간격)
 * - JSON: DB에서 이벤트 읽어서 생성 (녹화 종료 시)
 */
class HybridSensorLogger(
    private val videoFile: File,
    private val recordingStartTime: Long
) {
    private val srtLogger = SrtSensorLogger(recordingStartTime)

    private val srtFile: File
        get() = File(videoFile.parent, videoFile.nameWithoutExtension + ".srt")

    private val jsonFile: File
        get() = File(videoFile.parent, videoFile.nameWithoutExtension + "_events.json")

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

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
     * 녹화 종료 시: SRT 저장 + DB에서 이벤트 읽어서 JSON 생성
     */
    suspend fun finalize(eventDao: EventDao) {
        try {
            // 1. SRT 파일 저장
            srtLogger.save(srtFile)

            // 2. DB에서 이 녹화 세션의 이벤트들 조회
            val events = eventDao.getEventsByRecordingStartTime(recordingStartTime)

            // 3. JSON 파일 생성
            createJsonFromDb(events)

            Log.i(TAG, "✅ 하이브리드 로그 저장 완료:")
            Log.i(TAG, "   📄 SRT: ${srtFile.name}")
            Log.i(TAG, "   📄 JSON: ${jsonFile.name} (${events.size}개 이벤트)")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 하이브리드 로그 저장 실패", e)
        }
    }

    /**
     * DB에서 읽은 이벤트들을 JSON 파일로 저장
     */
    private fun createJsonFromDb(events: List<EventEntity>) {
        val jsonData = mapOf(
            "videoFile" to videoFile.name,
            "recordingStartTime" to recordingStartTime,
            "recordingDate" to java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(recordingStartTime)),
            "totalEvents" to events.size,
            "events" to events.map { event ->
                mapOf(
                    "eventId" to event.id,
                    "eventType" to event.type,
                    "timestamp" to event.timestamp,
                    "videoTimestamp" to (event.timestamp - recordingStartTime), // 영상 내 상대 시간
                    "location" to if (event.latitude != null && event.longitude != null) {
                        mapOf(
                            "latitude" to event.latitude,
                            "longitude" to event.longitude
                        )
                    } else null,
                    "speed" to event.speed,
                    "videoFilePath" to event.videoFilePath,
                    "extractedVideoPath" to event.extractedVideoPath,
                    "status" to event.status
                )
            }
        )

        try {
            val json = gson.toJson(jsonData)
            jsonFile.writeText(json)
            Log.d(TAG, "✅ JSON 파일 생성 완료: ${jsonFile.path}")
        } catch (e: Exception) {
            Log.e(TAG, "❌ JSON 파일 생성 실패", e)
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

    /**
     * JSON 파일 경로 반환
     */
    fun getJsonFilePath(): String = jsonFile.absolutePath

    companion object {
        private const val TAG = "HybridSensorLogger"
    }
}