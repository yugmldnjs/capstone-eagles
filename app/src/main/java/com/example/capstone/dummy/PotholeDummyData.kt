package com.example.capstone.dummy

import com.example.capstone.data.PotholeData
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI
import kotlin.random.Random

object PotholeDummyData {

    // 자전거 많이 다닐 법한 장소들 (임의)
    private val spots = listOf(
        Spot("jamsil_hangang", 37.5209, 127.1035),
        Spot("yanghwa_hangang", 37.5482, 126.9123),
        Spot("nanji_hangang", 37.5519, 126.8680),
        Spot("gwanggyo_lake", 37.2682, 127.0746),
        Spot("jeju_tapdong", 33.5153, 126.5265)
    )

    data class Spot(val name: String, val lat: Double, val lon: Double)

    /**
     * 여러 명소 주변에 포트홀 더미 데이터 생성
     */
    fun generate(): List<PotholeData> {
        val list = mutableListOf<PotholeData>()
        val now = System.currentTimeMillis()

        spots.forEach { spot ->
            // 각 스팟마다 10~20개 정도 포트홀 생성
            val count = Random.nextInt(10, 21)
            repeat(count) { idx ->
                val offset = randomOffsetMeters(maxDist = 80.0) // 최대 80m 정도 반경

                val dLat = offset.first / 111_320.0
                val dLon = offset.second / (111_320.0 * cos(Math.toRadians(spot.lat)))

                val lat = spot.lat + dLat
                val lon = spot.lon + dLon

                list.add(
                    PotholeData(
                        id = "dummy_${spot.name}_$idx",
                        latitude = lat,
                        longitude = lon,
                        createdAt = now - Random.nextLong(0, 60 * 60 * 1000) // 최근 1시간 이내
                    )
                )
            }
        }

        return list
    }

    /**
     * 0 ~ maxDist(m) 사이에서 랜덤 반경·각도로 오프셋 생성 (x: 동서, y: 남북)
     */
    private fun randomOffsetMeters(maxDist: Double): Pair<Double, Double> {
        val r = Random.nextDouble(0.0, maxDist)
        val theta = Random.nextDouble(0.0, 2 * PI)
        val dx = r * cos(theta)   // 동서 방향(m)
        val dy = r * sin(theta)   // 남북 방향(m)
        return dy to dx           // (dLatMeters, dLonMeters) 순서로 반환
    }
}
