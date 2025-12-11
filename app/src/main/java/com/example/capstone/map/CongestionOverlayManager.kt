package com.example.capstone.map

import android.util.Log
import com.example.capstone.BuildConfig
import com.example.capstone.data.LocationData
import com.example.capstone.data.LocationRepository
import com.example.capstone.dummy.BikeDummyData
import com.example.capstone.utils.CongestionCalculator
import com.example.capstone.utils.CongestionCluster
import com.example.capstone.utils.CongestionLevel
import com.example.capstone.utils.LocationUtils
import com.google.firebase.firestore.ListenerRegistration
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.NaverMap
import com.naver.maps.map.overlay.CircleOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import kotlin.math.*

class CongestionOverlayManager(
    private val naverMap: NaverMap,
    private val repo: LocationRepository,
    private val onFocusCamera: (lat: Double, lon: Double, zoom: Double) -> Unit
) {

    companion object {
        private const val TAG = "CongestionOverlayMgr"
    }

    private var locationListener: ListenerRegistration? = null
    private val clusterCircles = mutableListOf<CircleOverlay>()
    private val clusterMarkers = mutableListOf<Marker>()
    private var lastLocations: List<LocationData> = emptyList()

    var showCongestion: Boolean = true
        set(value) {
            field = value
            if (!value) {
                hideOverlays()
            } else {
                if (lastLocations.isNotEmpty()) {
                    updateCongestionClusters(lastLocations)
                }
            }
        }

    fun start() {
        if (locationListener != null) return

        locationListener = repo.listenRecentLocations(minutesAgo = 2) { realLocations ->
            val finalLocations =
                if (BuildConfig.USE_DUMMY_BIKE_DATA) {
                    realLocations + BikeDummyData.generate()
                } else {
                    realLocations
                }

            lastLocations = finalLocations
            updateCongestionClusters(finalLocations)
        }
    }

    fun stop() {
        locationListener?.remove()
        locationListener = null
        clear()
    }

    fun clear() {
        hideOverlays()
        clusterCircles.clear()
        clusterMarkers.clear()
        lastLocations = emptyList()
    }

    private fun hideOverlays() {
        clusterCircles.forEach { it.map = null }
        clusterMarkers.forEach { it.map = null }
    }

    private fun updateCongestionClusters(locations: List<LocationData>) {
        if (!showCongestion) {
            hideOverlays()
            return
        }

        val clusterRadius = CongestionCalculator.DEFAULT_RADIUS_METERS

        try {
            Log.d(TAG, "실제 사용자 위치 ${locations.size}개로 혼잡도 계산")

            // 150m 반경으로 클러스터 생성
            val clusters = CongestionCalculator.createClusters(
                locations,
                radiusMeters = clusterRadius
            )

            // ✅ 5명 미만은 전부 숨김
            val displayClusters = clusters.filter { it.userCount >= 5 }
            Log.d(TAG, "표시 대상 클러스터: ${displayClusters.size}개 (5명 이상만 표시)")

            // Circle / Marker 풀 확장 (재사용)
            while (clusterCircles.size < displayClusters.size) {
                clusterCircles.add(CircleOverlay())
            }
            while (clusterMarkers.size < displayClusters.size) {
                clusterMarkers.add(Marker())
            }

            displayClusters.forEachIndexed { index, cluster ->
                // 1. 원형 오버레이 설정
                val circle = clusterCircles[index]
                circle.apply {
                    center = LatLng(cluster.centerLat, cluster.centerLon)
                    radius = clusterRadius
                    color = addAlphaToColor(cluster.level.color, 0.55f)
                    outlineColor = addAlphaToColor(cluster.level.color, 0.86f)
                    outlineWidth = 6
                    map = naverMap
                }

                // 2. 중앙 숫자 마커 설정
                val marker = clusterMarkers[index]
                marker.apply {
                    position = LatLng(cluster.centerLat, cluster.centerLon)
                    icon = createMarkerIcon(cluster.userCount, cluster.level)
                    width = 80   // dp 기준
                    height = 80  // dp 기준
                    map = naverMap

                    setOnClickListener {
                        onFocusCamera(cluster.centerLat, cluster.centerLon, 15.0)
                        true
                    }
                }
            }

            // 남는 오버레이는 지도에서만 제거 (객체는 유지해서 재사용)
            for (i in displayClusters.size until clusterCircles.size) {
                clusterCircles[i].map = null
            }
            for (i in displayClusters.size until clusterMarkers.size) {
                clusterMarkers[i].map = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "클러스터 업데이트 실패", e)
        }
    }

    private fun createMarkerIcon(count: Int, level: CongestionLevel): OverlayImage {
        val sizeDp = 80 // dp
        val density = naverMap.context.resources.displayMetrics.density
        val pixelSize = (sizeDp * density).toInt()

        val bitmap = android.graphics.Bitmap.createBitmap(
            pixelSize,
            pixelSize,
            android.graphics.Bitmap.Config.ARGB_8888
        )
        val canvas = android.graphics.Canvas(bitmap)

        // 1) 원형 배경 (레벨 색)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            color = level.color
        }

        val centerX = pixelSize / 2f
        val centerY = pixelSize / 2f
        val radius = pixelSize / 2.5f  // 예전 MapFragment처럼 살짝 작은 원

        canvas.drawCircle(centerX, centerY, radius, paint)

        // 2) 흰색 테두리
        val strokePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            color = android.graphics.Color.WHITE
            strokeWidth = 4f * density
        }
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        // 3) 중앙 숫자 (유저 수)
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = android.graphics.Color.WHITE
            textSize = 24f * density
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val text = count.toString()
        val textBounds = android.graphics.Rect()
        textPaint.getTextBounds(text, 0, text.length, textBounds)
        val textY = centerY - textBounds.exactCenterY()

        canvas.drawText(text, centerX, textY, textPaint)

        return OverlayImage.fromBitmap(bitmap)
    }

    private fun addAlphaToColor(color: Int, alpha: Float): Int {
        val alphaInt = (alpha * 255).toInt()
        return android.graphics.Color.argb(
            alphaInt,
            android.graphics.Color.red(color),
            android.graphics.Color.green(color),
            android.graphics.Color.blue(color)
        )
    }
}
