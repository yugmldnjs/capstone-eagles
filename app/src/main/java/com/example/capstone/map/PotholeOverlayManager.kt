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
        private const val MIN_POTHOLE_EVENT_INTERVAL_MS = 2000L
        private const val EXISTING_PIN_DISTANCE_METERS = 20.0
    }

    private val potholeMarkers = mutableListOf<Marker>()
    private val potholePoints = mutableListOf<PotholeData>()
    private var potholeListener: ListenerRegistration? = null
    private var lastPotholeEventTime: Long = 0L

    // ✅ 현재 메모리에 로드된 포트홀 리스트 (복사본 반환)
    fun getCurrentPotholes(): List<PotholeData> = potholePoints.toList()

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
     * 모델/추적기에서 "지금 위치에 포트홀 확정" 신호가 왔을 때 호출
     * @return 이번 호출로 실제 새로운 포트홀 핀을 추가했으면 true, 아니면 false
     */
    fun addPotholeFromLocation(lat: Double, lon: Double): Boolean {
        val now = System.currentTimeMillis()

        // 1) 너무 자주 들어오는 이벤트는 막기
        if (now - lastPotholeEventTime < MIN_POTHOLE_EVENT_INTERVAL_MS) {
            Log.d(TAG, "포트홀 이벤트 너무 짧은 간격, 무시")
            return false
        }

        // 2) 20m 안에 이미 핀이 있으면 같은 포트홀로 보고 새로 추가하지 않음
        val hasNearbyPin = potholePoints.any { p ->
            LocationUtils.calculateDistance(p.latitude, p.longitude, lat, lon) <
                    EXISTING_PIN_DISTANCE_METERS
        }
        if (hasNearbyPin) {
            Log.d(TAG, "기존 포트홀(20m 이내) 존재 → 새 핀 추가하지 않음")
            lastPotholeEventTime = now      // 알림도 너무 자주 울리지 않게 시간은 갱신
            return false
        }

        // 3) 여기까지 왔으면 "새로운 포트홀"로 보고 실제 추가
        lastPotholeEventTime = now
        addOrMergePothole(lat, lon)
        return true
    }


    private fun addOrMergePothole(lat: Double, lon: Double) {
        val newPothole = PotholeData(
            latitude = lat,
            longitude = lon,
            createdAt = System.currentTimeMillis(),
            count = 1
        )
        potholePoints.add(newPothole)

        updatePotholeMarkers(potholePoints)
        potholeRepo.uploadPothole(newPothole.latitude, newPothole.longitude)
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
