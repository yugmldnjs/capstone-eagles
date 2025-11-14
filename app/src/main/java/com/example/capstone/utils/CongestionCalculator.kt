package com.example.capstone.utils

import android.graphics.Color
import com.example.capstone.data.LocationData
import kotlin.math.*

/**
 * 혼잡도 클러스터 정보
 */
data class CongestionCluster(
    val centerLat: Double,
    val centerLon: Double,
    val userCount: Int,
    val level: CongestionLevel
)

/**
 * 혼잡도 레벨
 */
enum class CongestionLevel(val color: Int, val displayName: String) {
    LOW(Color.rgb(76, 175, 80), "여유"),      // 초록색
    MEDIUM(Color.rgb(255, 193, 7), "보통"),   // 노란색
    HIGH(Color.rgb(244, 67, 54), "혼잡")      // 빨간색
}

/**
 * 위치 기반 혼잡도 클러스터링
 */
object CongestionCalculator {

    /**
     * 사용자 위치 데이터를 클러스터로 변환
     * @param locations 사용자 위치 리스트
     * @param radiusMeters 클러스터 반경 (미터)
     * @return 클러스터 리스트
     */
    fun createClusters(
        locations: List<LocationData>,
        radiusMeters: Double = 100.0
    ): List<CongestionCluster> {
        if (locations.isEmpty()) return emptyList()

        val clusters = mutableListOf<CongestionCluster>()
        val processed = mutableSetOf<String>()

        for (location in locations) {
            if (location.userId in processed) continue

            // 현재 위치 주변의 사용자들 찾기
            val nearbyUsers = locations.filter { other ->
                calculateDistance(
                    location.latitude, location.longitude,
                    other.latitude, other.longitude
                ) <= radiusMeters
            }

            // 처리된 것으로 마킹
            processed.addAll(nearbyUsers.map { it.userId })

            val userCount = nearbyUsers.size

            // 중심점 계산 (평균 위치)
            val centerLat = nearbyUsers.map { it.latitude }.average()
            val centerLon = nearbyUsers.map { it.longitude }.average()

            // ✅ 혼잡도 레벨 결정 (변경됨: 5명 이상 보통, 10명 이상 혼잡)
            val level = when {
                userCount >= 10 -> CongestionLevel.HIGH    // 10명 이상: 혼잡
                userCount >= 5 -> CongestionLevel.MEDIUM   // 5~9명: 보통
                else -> CongestionLevel.LOW                // 1~4명: 여유
            }

            clusters.add(
                CongestionCluster(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    userCount = userCount,
                    level = level
                )
            )
        }

        return clusters
    }

    /**
     * 두 지점 간 거리 계산 (미터 단위)
     * Haversine formula 사용
     */
    private fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0 // 지구 반경 (미터)

        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * 특정 위치의 혼잡도 레벨 가져오기
     */
    fun getCongestionAtLocation(
        targetLat: Double,
        targetLon: Double,
        locations: List<LocationData>,
        radiusMeters: Double = 150.0
    ): CongestionLevel {
        val nearbyCount = locations.count { location ->
            calculateDistance(
                targetLat, targetLon,
                location.latitude, location.longitude
            ) <= radiusMeters
        }

        return when {
            nearbyCount >= 10 -> CongestionLevel.HIGH   // 10명 이상: 혼잡
            nearbyCount >= 5 -> CongestionLevel.MEDIUM  // 5~9명: 보통
            else -> CongestionLevel.LOW                 // 1~4명: 여유
        }
    }
}