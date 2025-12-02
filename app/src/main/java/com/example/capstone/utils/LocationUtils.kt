package com.example.capstone.utils

import kotlin.math.*

object LocationUtils {

    fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    // ✅ 주행 방향(방위각) 계산: 0~360도, 0=북, 90=동
    fun calculateBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        var brng = Math.toDegrees(atan2(y, x))
        brng = (brng + 360.0) % 360.0
        return brng
    }

    // 위치 업로드용 양자화 (지금 MapFragment에 있는 로직 그대로)
    fun quantizeLatLon(lat: Double, lon: Double, factor: Double = 1000.0): Pair<Double, Double> {
        val qLat = kotlin.math.round(lat * factor) / factor
        val qLon = kotlin.math.round(lon * factor) / factor
        return qLat to qLon
    }
}
