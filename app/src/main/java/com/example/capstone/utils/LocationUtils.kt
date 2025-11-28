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

    // 위치 업로드용 양자화 (지금 MapFragment에 있는 로직 그대로)
    fun quantizeLatLon(lat: Double, lon: Double, factor: Double = 1000.0): Pair<Double, Double> {
        val qLat = kotlin.math.round(lat * factor) / factor
        val qLon = kotlin.math.round(lon * factor) / factor
        return qLat to qLon
    }
}
