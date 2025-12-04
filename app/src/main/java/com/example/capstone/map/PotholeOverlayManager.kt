package com.example.capstone.map

import android.content.Context
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.example.capstone.R
import com.example.capstone.data.PotholeData
import com.example.capstone.data.PotholeRepository
import com.example.capstone.utils.LocationUtils
import com.google.firebase.firestore.ListenerRegistration
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.InfoWindow
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import android.graphics.Bitmap

class PotholeOverlayManager(
    private val context: Context,
    private val naverMap: NaverMap,
    private val potholeRepo: PotholeRepository,
    private val onFocusCamera: (lat: Double, lon: Double, zoom: Double) -> Unit,
    private val onReportClick: (pothole: PotholeData) -> Unit
) {

    companion object {
        private const val TAG = "PotholeOverlayMgr"
        private const val MIN_POTHOLE_EVENT_INTERVAL_MS = 2000L
        private const val EXISTING_PIN_DISTANCE_METERS = 5.0
    }

    private val potholeMarkers = mutableListOf<Marker>()
    private val potholePoints = mutableListOf<PotholeData>()
    private var potholeListener: ListenerRegistration? = null
    private var lastPotholeEventTime: Long = 0L

    // 단일 InfoWindow 인스턴스를 여러 마커에서 재사용
    private val infoWindow = InfoWindow().apply {
        adapter = object : InfoWindow.DefaultViewAdapter(context) {
            override fun getContentView(window: InfoWindow): View {
                val view = LayoutInflater.from(context)
                    .inflate(R.layout.view_pothole_info_window, null)

                val title = view.findViewById<TextView>(R.id.tv_title)

                // 이 InfoWindow가 올라간 마커 → tag 에 PotholeData 들어있음
                val marker = window.marker
                val pothole = marker?.tag as? PotholeData

                title.text = "포트홀"

                // ⚠️ 여기에서 버튼에 클릭 리스너 달지 말고, 그냥 UI만 그리게 둔다
                return view
            }
        }

        // ✅ InfoWindow 전체를 클릭했을 때 “신고” 동작 실행
        setOnClickListener { overlay ->
            val window = overlay as InfoWindow
            val marker = window.marker
            val pothole = marker?.tag as? PotholeData

            window.close()

            pothole?.let {
                onReportClick(it)  // MapFragment.showPotholeReportBottomSheet 호출
            }
            true
        }
    }

    fun closeInfoWindow() {
        infoWindow.close()
    }

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

    fun addPotholeFromLocation(
        lat: Double,
        lon: Double,
        photoBitmap: Bitmap?
    ): Boolean {
        val now = System.currentTimeMillis()

        // 1) 너무 자주 들어오는 이벤트 방지
        if (now - lastPotholeEventTime < MIN_POTHOLE_EVENT_INTERVAL_MS) {
            Log.d(TAG, "포트홀 이벤트 너무 짧은 간격, 무시")
            return false
        }

        // 2) 5m 안에 이미 핀이 있으면 새로 추가 X
        val hasNearbyPin = potholePoints.any { p ->
            LocationUtils.calculateDistance(p.latitude, p.longitude, lat, lon) <
                    EXISTING_PIN_DISTANCE_METERS
        }
        if (hasNearbyPin) {
            Log.d(TAG, "기존 포트홀(5m 이내) 존재 → 새 핀/업로드 모두 안 함")
            lastPotholeEventTime = now
            return false
        }

        // 3) 진짜 새 포트홀
        lastPotholeEventTime = now
        addPothole(lat, lon, photoBitmap)
        return true
    }

    private fun addPothole(
        lat: Double,
        lon: Double,
        photoBitmap: Bitmap?
    ) {
        Log.d("PotholeOverlayMgr", "addPothole: photo=${photoBitmap != null}")
        val newPothole = PotholeData(
            latitude = lat,
            longitude = lon,
            createdAt = System.currentTimeMillis(),
            imageUrl = null // URL은 업로드 이후에 Firestore에서 다시 받아올 것
        )

        // 로컬 리스트 / 마커 먼저 업데이트
        potholePoints.add(newPothole)
        updatePotholeMarkers(potholePoints)

        // Firestore + Storage 업로드
        potholeRepo.uploadPothole(
            lat = newPothole.latitude,
            lon = newPothole.longitude,
            photoBitmap = photoBitmap
        ) { success ->
            Log.d(TAG, "uploadPothole result: $success")
            // 성공/실패 여부는 지금은 그냥 로그만
            // 실제 데이터는 listenAllPotholes() 스냅샷으로 다시 들어올 거라
            // potholePoints는 자동으로 최신 상태가 된다.
        }
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
            marker.tag = pothole           // ✅ InfoWindow에서 PotholeData 꺼내기용
            marker.map = naverMap

            marker.setOnClickListener { overlay ->
                val clickedMarker = overlay as Marker

                // 1) 카메라 줌인
                onFocusCamera(pothole.latitude, pothole.longitude, 17.0)

                // 2) 인포윈도우 토글
                if (clickedMarker.infoWindow == null) {
                    infoWindow.open(clickedMarker)
                } else {
                    infoWindow.close()
                }
                true
            }
        }

        // 남는 마커들은 지도에서만 숨김
        for (i in potholes.size until potholeMarkers.size) {
            potholeMarkers[i].map = null
        }
    }
}
