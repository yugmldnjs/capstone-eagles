package com.example.capstone.data

/**
 * 지도에 찍을 포트홀 1개의 정보
 */
data class PotholeData(
    val id: String? = null,     // Firestore 문서 ID
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val imageUrl: String? = null
)