package com.example.capstone.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EventDao {
    // 이벤트 추가 (충격 감지 시 호출)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: EventEntity)

    // 이벤트 업데이트 (추출 완료 시 상태 변경)
    @Update
    suspend fun update(event: EventEntity)

    // 추출 대기 중인 이벤트 가져오기
    @Query("SELECT * FROM events WHERE status = 'pending' ORDER BY timestamp DESC")
    suspend fun getPendingExtractions(): List<EventEntity>

    // 모든 이벤트 가져오기 (UI 표시용)
    @Query("SELECT * FROM events ORDER BY timestamp DESC")
    fun getAllEvents(): Flow<List<EventEntity>>

    // 특정 시간의 이벤트 찾기
    @Query("SELECT * FROM events WHERE timestamp = :timestamp")
    suspend fun getEventByTimestamp(timestamp: Long): EventEntity?

    // 이벤트 삭제
    @Query("DELETE FROM events WHERE timestamp = :timestamp")
    suspend fun deleteEvent(timestamp: Long)

    // 특정 녹화 세션의 이벤트들 찾기
    @Query("SELECT * FROM events WHERE recordingStartTimestamp = :timestamp")
    suspend fun getEventsByRecordingTimestamp(timestamp: Long): List<EventEntity>

    // URI가 null인 pending 이벤트들
    @Query("SELECT * FROM events WHERE status = 'pending' AND videoUri IS NULL")
    suspend fun getEventsWaitingForUri(): List<EventEntity>
}
