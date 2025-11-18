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
 *
 * ✅ 기존: 각 위치마다 전체를 스캔하는 O(N²) 방식
 * ✅ 변경: 그리드(Spatial Grid) 기반으로 O(N)에 가깝게 최적화
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
        radiusMeters: Double = 150.0
    ): List<CongestionCluster> {
        if (locations.isEmpty()) return emptyList()

        // 평균 위도 기준으로 도(degree) ↔ 미터 변환값 계산
        val meanLat = locations.map { it.latitude }.average()
        val meanLatRad = Math.toRadians(meanLat)

        val metersPerDegLat = 111_320.0                // 위도 1도 ≈ 111.32km
        val metersPerDegLon = 111_320.0 * cos(meanLatRad).coerceAtLeast(0.000001)

        // 클러스터 반경을 기준으로 그리드 셀 크기 결정
        val cellSizeLat = radiusMeters / metersPerDegLat
        val cellSizeLon = radiusMeters / metersPerDegLon

        // 1) 위치들을 그리드에 분배
        val grid = HashMap<Pair<Int, Int>, MutableList<LocationData>>()

        for (loc in locations) {
            val gx = floor(loc.latitude / cellSizeLat).toInt()
            val gy = floor(loc.longitude / cellSizeLon).toInt()
            val key = gx to gy
            grid.getOrPut(key) { mutableListOf() }.add(loc)
        }

        // 2) 각 그리드 셀 + 주변 8셀만 보면서 클러스터 생성
        val visited = HashSet<Pair<Int, Int>>()
        val clusters = mutableListOf<CongestionCluster>()

        for ((key, _) in grid) {
            if (visited.contains(key)) continue

            val (gx, gy) = key
            val usersInCluster = mutableListOf<LocationData>()
            val cellsInThisCluster = mutableListOf<Pair<Int, Int>>()

            // 자기 칸 + 주변 8칸 → 최대 9칸만 검사
            for (dx in -1..1) {
                for (dy in -1..1) {
                    val nk = (gx + dx) to (gy + dy)
                    val list = grid[nk] ?: continue
                    usersInCluster.addAll(list)
                    cellsInThisCluster.add(nk)
                }
            }

            if (usersInCluster.isEmpty()) continue

            // 중심점 계산 (평균 위치)
            val centerLat = usersInCluster.map { it.latitude }.average()
            val centerLon = usersInCluster.map { it.longitude }.average()

            val userCount = usersInCluster.size

            // 혼잡도 레벨 결정 (기존 로직 유지: 10~24 보통, 25이상 혼잡)
            val level = when {
                userCount >= 25 -> CongestionLevel.HIGH
                userCount >= 10 -> CongestionLevel.MEDIUM
                else -> CongestionLevel.LOW
            }

            clusters.add(
                CongestionCluster(
                    centerLat = centerLat,
                    centerLon = centerLon,
                    userCount = userCount,
                    level = level
                )
            )

            // 이 클러스터에 포함된 모든 셀은 다시 처리하지 않도록 마킹
            visited.addAll(cellsInThisCluster)
        }

        return clusters
    }
}