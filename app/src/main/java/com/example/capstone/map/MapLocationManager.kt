package com.example.capstone.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import com.example.capstone.data.LocationRepository
import com.example.capstone.utils.LocationUtils
import com.google.android.gms.location.*
import com.google.firebase.auth.FirebaseAuth

class MapLocationManager(
    private val context: Context,
    private val repo: LocationRepository,
    private val auth: FirebaseAuth,
    /**
     * 위치가 갱신될 때마다 Fragment에 알려주는 콜백
     */
    private val onLocationChanged: (lat: Double, lon: Double) -> Unit
) {

    companion object {
        private const val TAG = "MapLocationManager"
        private const val UPLOAD_DISTANCE_THRESHOLD = 10.0  // 10m
        private const val UPLOAD_TIME_THRESHOLD = 30_000L   // 30초
    }

    private val fusedLocationClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationRequest: LocationRequest =
        LocationRequest.Builder(
            Priority.PRIORITY_BALANCED_POWER_ACCURACY, // 기존 설정 그대로
            5000L
        ).apply {
            setMinUpdateDistanceMeters(5f)
            setMinUpdateIntervalMillis(3000L)
            setWaitForAccurateLocation(false)
        }.build()

    var lastLat: Double? = null
        private set
    var lastLon: Double? = null
        private set

    private var lastUploadLat: Double? = null
    private var lastUploadLon: Double? = null
    private var lastUploadTime: Long = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            val location = locationResult.lastLocation ?: return

            val lat = location.latitude
            val lon = location.longitude
            if (lat == 0.0 || lon == 0.0) return

            lastLat = lat
            lastLon = lon

            // 1) UI 쪽으로 전달 (지도에 마커 / 카메라 이동)
            onLocationChanged(lat, lon)

            // 2) Firestore 업로드
            checkAndUploadLocation(lat, lon)
        }
    }

    fun start() {
        if (!hasLocationPermission()) {
            Log.d(TAG, "location permission not granted, skip start()")
            return
        }

        fusedLocationClient.requestLocationUpdates(
            locationRequest,
            locationCallback,
            Looper.getMainLooper()
        )
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return fine == PackageManager.PERMISSION_GRANTED ||
                coarse == PackageManager.PERMISSION_GRANTED
    }

    private fun checkAndUploadLocation(lat: Double, lon: Double) {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastUploadTime

        val distanceMoved = lastUploadLat?.let { lastLat ->
            lastUploadLon?.let { lastLon ->
                LocationUtils.calculateDistance(lastLat, lastLon, lat, lon)
            }
        } ?: Double.MAX_VALUE

        val shouldUpload =
            distanceMoved >= UPLOAD_DISTANCE_THRESHOLD || timeDiff >= UPLOAD_TIME_THRESHOLD

        if (!shouldUpload) return

        val userId = auth.currentUser?.uid ?: "anonymous"
        val (safeLat, safeLon) = LocationUtils.quantizeLatLon(lat, lon)

        Log.d(
            TAG,
            "사용자 위치 업로드: userId=$userId, lat=$safeLat, lon=$safeLon, " +
                    "moved=${"%.2f".format(distanceMoved)}m, diff=${timeDiff}ms"
        )

        repo.uploadLocation(userId, safeLat, safeLon)

        lastUploadLat = lat
        lastUploadLon = lon
        lastUploadTime = currentTime
    }
}
