package com.example.capstone.dummy

import com.example.capstone.data.LocationData
import kotlin.math.*

/**
 * 🚴 자전거 더미 데이터 생성기 (간소화 버전)
 *
 * ✅ 특징:
 * - 총 10개의 클러스터만 생성
 * - 혼잡도(여유/보통/혼잡) 명확하게 구분
 * - 한국 주요 자전거 명소 4곳
 * - 각 명소마다 2-3개 클러스터
 */
object BikeDummyData {

    /**
     * 더미 데이터 생성 메인 함수
     */
    fun generate(): List<LocationData> {
        val list = mutableListOf<LocationData>()
        val now = System.currentTimeMillis()

        bikeSpots.forEach { spot ->
            generateClustersForSpot(list, spot, now)
        }

        return list
    }

    /**
     * 각 명소별 클러스터 생성
     */
    private fun generateClustersForSpot(
        list: MutableList<LocationData>,
        spot: BikeSpot,
        timestamp: Long
    ) {
        spot.clusters.forEachIndexed { index, cluster ->
            // 클러스터 위치 계산 (극좌표 → 직교좌표)
            val dLat = (cluster.distance * cos(cluster.angle)) / 111320.0
            val dLon = (cluster.distance * sin(cluster.angle)) /
                    (111320.0 * cos(Math.toRadians(spot.lat)))

            val clusterCenterLat = spot.lat + dLat
            val clusterCenterLon = spot.lon + dLon

            // 클러스터 내부에 사용자 배치
            addUsersToCluster(
                list = list,
                centerLat = clusterCenterLat,
                centerLon = clusterCenterLon,
                count = cluster.userCount,
                timestamp = timestamp,
                spotName = spot.name,
                clusterIndex = index
            )
        }
    }

    /**
     * 클러스터 중심 주변에 사용자 분산 배치
     * 원형으로 일정한 간격으로 배치
     */
    private fun addUsersToCluster(
        list: MutableList<LocationData>,
        centerLat: Double,
        centerLon: Double,
        count: Int,
        timestamp: Long,
        spotName: String,
        clusterIndex: Int
    ) {
        repeat(count) { userIndex ->
            // 원형 분포 (고르게 분산)
            val angle = (userIndex.toDouble() / count) * 2 * PI

            // 거리는 중심에서 바깥으로 나선형 분포
            val ringIndex = userIndex / 8 // 8명씩 링을 구성
            val baseDistance = 20.0 + (ringIndex * 25.0) // 20m, 45m, 70m, 95m...
            val distance = baseDistance + (userIndex % 8) * 3.0 // 약간의 변화

            val dLat = (distance * cos(angle)) / 111320.0
            val dLon = (distance * sin(angle)) /
                    (111320.0 * cos(Math.toRadians(centerLat)))

            list.add(
                LocationData(
                    userId = "dummy_${spotName}_${clusterIndex}_$userIndex",
                    latitude = centerLat + dLat,
                    longitude = centerLon + dLon,
                    timestamp = timestamp
                )
            )
        }
    }

    /**
     * 클러스터 정보
     * @param distance 명소 중심에서의 거리 (미터)
     * @param angle 방향 (라디안)
     * @param userCount 사용자 수
     */
    data class Cluster(
        val distance: Double,
        val angle: Double,
        val userCount: Int
    )

    /**
     * 자전거 명소
     * @param name 명소 식별자
     * @param lat 위도
     * @param lon 경도
     * @param clusters 클러스터 목록
     */
    data class BikeSpot(
        val name: String,
        val lat: Double,
        val lon: Double,
        val clusters: List<Cluster>
    )

    /**
     * 🚴 한국 주요 자전거 명소 4곳 (총 10개 클러스터)
     *
     * 혼잡도 기준:
     * - 5~9명: 여유 (초록색)
     * - 10~24명: 보통 (노란색)
     * - 25명 이상: 혼잡 (빨간색)
     */
    private val bikeSpots = listOf(
        // 1. 잠실 한강공원 (3개 클러스터)
        BikeSpot(
            name = "jamsil_hangang",
            lat = 37.5209,
            lon = 127.1035,
            clusters = listOf(
                Cluster(200.0, 0.0, 32),           // 동쪽: 혼잡 (빨강)
                Cluster(250.0, PI / 2, 16),        // 북쪽: 보통 (노랑)
                Cluster(280.0, PI, 7)              // 서쪽: 여유 (초록)
            )
        ),

        // 2. 여의도 한강공원 (3개 클러스터)
        BikeSpot(
            name = "yeouido_hangang",
            lat = 37.5285,
            lon = 126.9345,
            clusters = listOf(
                Cluster(180.0, PI / 4, 28),        // 북동: 혼잡 (빨강)
                Cluster(240.0, PI * 3 / 4, 14),    // 북서: 보통 (노랑)
                Cluster(260.0, PI * 5 / 4, 6)      // 남서: 여유 (초록)
            )
        ),

        // 3. 부산 광안리 (2개 클러스터)
        BikeSpot(
            name = "busan_gwangan",
            lat = 35.1571,
            lon = 129.1608,
            clusters = listOf(
                Cluster(220.0, 0.0, 30),           // 동쪽: 혼잡 (빨강)
                Cluster(250.0, PI, 12)             // 서쪽: 보통 (노랑)
            )
        ),

        // 4. 일산 호수공원 (2개 클러스터)
        BikeSpot(
            name = "ilsan_lake",
            lat = 37.6290,
            lon = 126.8705,
            clusters = listOf(
                Cluster(230.0, PI / 2, 18),        // 북쪽: 보통 (노랑)
                Cluster(270.0, PI * 3 / 2, 8)      // 남쪽: 여유 (초록)
            )
        )
    )
}