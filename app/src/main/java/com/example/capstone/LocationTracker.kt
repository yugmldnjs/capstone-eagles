package com.example.capstone.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import com.skt.tmap.TMapGpsManager

/**
 * TMapGpsManager를 감싼 위치 추적 도우미 클래스
 */
class LocationTracker(
    private val context: Context,
    private val onLocationChanged: (Double, Double) -> Unit
) {
    private var gps: TMapGpsManager? = null

    fun start() {
        if (!hasPermission()) {
            Log.w("LocationTracker", "위치 권한 없음")
            return
        }

        gps = TMapGpsManager(context).apply {
            minTime = 2000L
            minDistance = 3f
            provider = TMapGpsManager.PROVIDER_GPS
            setOnLocationChangeListener { location ->
                val lat = location.latitude
                val lon = location.longitude
                onLocationChanged(lat, lon)
            }
            openGps()
        }
    }

    fun stop() {
        gps?.closeGps()
        gps = null
    }

    private fun hasPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }
}
