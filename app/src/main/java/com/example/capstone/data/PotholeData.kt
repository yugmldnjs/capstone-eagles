package com.example.capstone.data

/**
 * 지도에 찍을 포트홀 1개의 정보
 */
data class PotholeData(
    val id: String? = null,     // 나중에 Firestore 문서 ID (지금은 null 가능)
    val latitude: Double,
    val longitude: Double,
    val createdAt: Long = System.currentTimeMillis(),
    val count: Int = 1          // 해당 위치에서 몇 번 감지됐는지 (나중을 위한 필드)
)
