package com.example.capstone.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey
    val timestamp: Long,                    // 이벤트 발생 시간 (밀리초)
    val recordingStartTimestamp: Long,      // 녹화 시작 시간 (밀리초)
    val type: String,                       // "impact", "emergency_brake", "manual"
    val latitude: Double?,                  // GPS 위도
    val longitude: Double?,                 // GPS 경도
    val speed: Float?,                      // 속도 (km/h)
    val accelerometerX: Float,              // 가속도계 X축
    val accelerometerY: Float,              // 가속도계 Y축
    val accelerometerZ: Float,              // 가속도계 Z축
    val gyroX: Float?,                      // 자이로스코프 X축
    val gyroY: Float?,                      // 자이로스코프 Y축
    val gyroZ: Float?,                      // 자이로스코프 Z축
    val videoFilePath: String?,                     // 원본 녹화 파일 경로
    val extractedVideoPath: String?,        // 추출된 이벤트 영상 경로
    val status: String,                     // "pending", "extracting", "completed", "failed"
    val createdAt: Long = System.currentTimeMillis()  // DB 저장 시간
)
