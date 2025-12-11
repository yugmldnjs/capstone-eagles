package com.example.capstone.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: Long,                                // 이벤트 발생 시간 (밀리초)
    val recordingStartTimestamp: Long,                  // 녹화 시작 시간 (밀리초)
    val type: String,                                   // "fall", "sudden_brake"
    val latitude: Double?,                              // GPS 위도
    val longitude: Double?,                             // GPS 경도
    val speed: Float?,                                  // 속도 (km/h)
    val videoFilePath: String?,                         // 원본 녹화 파일 경로
    val extractedVideoPath: String?,                    // 추출된 이벤트 영상 경로
    val status: String,                                 // "pending", "extracting", "completed", "failed"
    val createdAt: Long = System.currentTimeMillis()  // DB 저장 시간
)
