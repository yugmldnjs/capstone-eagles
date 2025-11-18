package com.example.capstone.dummy

import com.example.capstone.data.LocationData
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

object BikeDummyData {

    fun generate(): List<LocationData> {
        val list = mutableListOf<LocationData>()
        val now = System.currentTimeMillis()

        bikeSpots.forEach { spot ->
            generateFixedClustersForSpot(list, spot, now)
        }

        return list
    }

    // 한국 주요 자전거 명소
    private val bikeSpots = listOf(
        BikeSpot("jamsil_hangang", 37.5209, 127.1035),   // 잠실 한강공원
        BikeSpot("yanghwa_hangang", 37.5482, 126.9123),  // 양화 한강공원
        BikeSpot("nanji_hangang", 37.5519, 126.8680),    // 난지 한강공원
        BikeSpot("gwangan_beach", 35.1571, 129.1608),    // 광안리
        BikeSpot("ilsan_lake", 37.6290, 126.8705),       // 일산 호수공원
        BikeSpot("gwanggyo_lake", 37.2682, 127.0746),    // 광교호수공원
        BikeSpot("chuncheon_lake", 37.8815, 127.7298),   // 의암호
        BikeSpot("jeju_tapdong", 33.5153, 126.5265)      // 제주 탑동 자전거길
    )

    data class BikeSpot(
        val name: String,
        val lat: Double,
        val lon: Double
    )

    // 고정된 offset 패턴 (반경 180~260m)
    private val fixedOffsets = listOf(
        PolarOffset(200.0, 0.0),             // 동쪽
        PolarOffset(220.0, PI / 2),          // 북쪽
        PolarOffset(260.0, PI),              // 서쪽
        PolarOffset(180.0, PI * 3 / 2)       // 남쪽
    )

    data class PolarOffset(val dist: Double, val angle: Double)

    private fun generateFixedClustersForSpot(
        list: MutableList<LocationData>,
        spot: BikeSpot,
        timestamp: Long
    ) {
        // 혼잡 / 보통 / 여유 인구를 고정된 순서대로 배치
        val populationPattern = listOf(
            28, // 혼잡
            15, // 보통
            7,  // 여유
            12  // 보통2
        )

        populationPattern.forEachIndexed { idx, count ->
            val offset = fixedOffsets[idx % fixedOffsets.size]

            val dLat = (offset.dist * cos(offset.angle)) / 111320.0
            val dLon = (offset.dist * sin(offset.angle)) /
                    (111320.0 * cos(Math.toRadians(spot.lat)))

            val centerLat = spot.lat + dLat
            val centerLon = spot.lon + dLon

            addUsersAroundCenter(list, centerLat, centerLon, count, timestamp, spot.name, idx)
        }
    }

    // 150m 내부 흩뿌림 (고정 반경)
    private fun addUsersAroundCenter(
        list: MutableList<LocationData>,
        centerLat: Double,
        centerLon: Double,
        count: Int,
        timestamp: Long,
        spotName: String,
        clusterIndex: Int
    ) {
        repeat(count) { userIndex ->
            val angle = (userIndex.toDouble() / count) * 2 * PI  // 일정 간격으로 분포
            val dist = 30.0 + (userIndex % 5) * 10               // 30~70m 고정 분포

            val dLat = (dist * cos(angle)) / 111320.0
            val dLon = (dist * sin(angle)) /
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
}