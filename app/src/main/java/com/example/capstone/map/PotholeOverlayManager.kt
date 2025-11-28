package com.example.capstone.map

import android.util.Log
import com.example.capstone.data.PotholeData
import com.example.capstone.data.PotholeRepository
import com.example.capstone.utils.LocationUtils
import com.google.firebase.firestore.ListenerRegistration
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage

class PotholeOverlayManager(
    private val naverMap: NaverMap,
    private val potholeRepo: PotholeRepository,
    private val onFocusCamera: (lat: Double, lon: Double, zoom: Double) -> Unit
) {

    companion object {
        private const val TAG = "PotholeOverlayMgr"
        private const val POTHOLE_MERGE_DISTANCE_METERS = 5.0
        private const val MIN_POTHOLE_EVENT_INTERVAL_MS = 2000L
    }

    private val potholeMarkers = mutableListOf<Marker>()
    private val potholePoints = mutableListOf<PotholeData>()
    private var potholeListener: ListenerRegistration? = null
    private var lastPotholeEventTime: Long = 0L

    var showPotholeMarkers: Boolean = true
        set(value) {
            field = value
            if (!value) {
                potholeMarkers.forEach { it.map = null }
            } else {
                updatePotholeMarkers(potholePoints)
            }
        }

    fun start() {
        if (potholeListener != null) return

        potholeListener = potholeRepo.listenAllPotholes { serverPotholes ->
            potholePoints.clear()
            potholePoints.addAll(serverPotholes)
            updatePotholeMarkers(potholePoints)
        }
    }

    fun stop() {
        potholeListener?.remove()
        potholeListener = null
        clear()
    }

    fun clear() {
        potholeMarkers.forEach { it.map = null }
        potholeMarkers.clear()
        potholePoints.clear()
    }

    /**
     * 모델에서 "현재 위치에 포트홀 발견" 신호 들어왔을 때 호출
     */
    fun addPotholeFromLocation(lat: Double, lon: Double) {
        val now = System.currentTimeMillis()
        if (now - lastPotholeEventTime < MIN_POTHOLE_EVENT_INTERVAL_MS) {
            Log.d(TAG, "포트홀 이벤트 너무 짧은 간격, 무시")
            return
        }
        lastPotholeEventTime = now

        addOrMergePothole(lat, lon)
    }

    private fun addOrMergePothole(lat: Double, lon: Double) {
        val existing = potholePoints.firstOrNull { p ->
            LocationUtils.calculateDistance(p.latitude, p.longitude, lat, lon) <
                    POTHOLE_MERGE_DISTANCE_METERS
        }

        val targetLat: Double
        val targetLon: Double

        if (existing != null) {
            val index = potholePoints.indexOf(existing)
            val updated = existing.copy(
                count = existing.count + 1,
                createdAt = System.currentTimeMillis()
            )
            potholePoints[index] = updated
            targetLat = updated.latitude
            targetLon = updated.longitude
        } else {
            val newPothole = PotholeData(
                latitude = lat,
                longitude = lon,
                createdAt = System.currentTimeMillis(),
                count = 1
            )
            potholePoints.add(newPothole)
            targetLat = newPothole.latitude
            targetLon = newPothole.longitude
        }

        updatePotholeMarkers(potholePoints)
        potholeRepo.uploadPothole(targetLat, targetLon)
    }

    private fun updatePotholeMarkers(potholes: List<PotholeData>) {
        if (!showPotholeMarkers) {
            potholeMarkers.forEach { it.map = null }
            return
        }

        // 마커 풀을 필요한 만큼 늘리기
        while (potholeMarkers.size < potholes.size) {
            potholeMarkers.add(
                Marker().apply {
                    icon = OverlayImage.fromResource(
                        com.naver.maps.map.R.drawable.navermap_default_marker_icon_black
                    )
                    width = 70
                    height = 70
                }
            )
        }

        potholes.forEachIndexed { index, pothole ->
            val marker = potholeMarkers[index]
            marker.position = LatLng(pothole.latitude, pothole.longitude)
            marker.map = naverMap

            marker.setOnClickListener {
                onFocusCamera(pothole.latitude, pothole.longitude, 17.0)
                true
            }
        }

        // 남는 마커들은 지도에서만 숨김
        for (i in potholes.size until potholeMarkers.size) {
            potholeMarkers[i].map = null
        }
    }
}
