package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.google.android.gms.location.*
import com.skt.tmap.TMapView
import com.skt.tmap.TMapPoint
import com.skt.tmap.overlay.TMapCircle
import com.example.capstone.data.LocationRepository
import com.example.capstone.data.LocationData
import com.example.capstone.utils.CongestionCalculator
import com.example.capstone.utils.CongestionCluster
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlin.math.*


class MapFragment : Fragment(R.layout.fragment_map) {

    private lateinit var tMapView: TMapView

    // Google Location Services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    // 마지막으로 받은 내 위치
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // ✅ 위치 업로드 조건 체크용
    private var lastUploadLat: Double? = null
    private var lastUploadLon: Double? = null
    private var lastUploadTime = 0L

    // 지도 시작 시 true로 시작 → 첫 위치에 자동 고정
    private var followMyLocation: Boolean = true

    // 권한 요청 코드
    private val REQ_LOCATION = 1000
    private var isFirstLocation = true
    private var isMapReady = false
    private lateinit var repo: LocationRepository
    private lateinit var auth: FirebaseAuth

    // ✅ 혼잡도 관련 변수
    private var locationListener: ListenerRegistration? = null
    private val clusterCircles = mutableListOf<String>() // 원형 오버레이 ID 저장

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = LocationRepository()
        auth = FirebaseAuth.getInstance()

        // 1) 지도 초기화
        tMapView = TMapView(requireContext()).apply {
            setSKTMapApiKey(BuildConfig.TMAP_API_KEY)
            setZoomLevel(15)
            setIconVisibility(true)

            // 지도 초기화 완료 리스너
            setOnMapReadyListener {
                isMapReady = true
                Log.d(TAG, "TMapView 초기화 완료")

                // ✅ 테스트용: GPS 없어도 지도가 서울시청으로 이동
                val defaultLat = 37.5665
                val defaultLon = 126.9780

                try {
                    // 이미 위치를 받았다면 그 위치로, 아니면 서울시청으로
                    val targetLat = lastLat ?: defaultLat
                    val targetLon = lastLon ?: defaultLon

                    setCenterPoint(targetLat, targetLon)
                    Log.d(TAG, "지도 중심 설정: lat=$targetLat, lon=$targetLon")

                    // 내 위치가 있으면 마커도 표시
                    lastLat?.let { lat ->
                        lastLon?.let { lon ->
                            setLocationPoint(lon, lat)
                            if (followMyLocation) {
                                setCenterPoint(lat, lon)
                            }
                            Log.d(TAG, "지도 초기화 후 위치 설정 완료")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "지도 초기화 후 위치 설정 실패", e)
                }

                // ✅ 지도 준비 완료 후 혼잡도 리스너 시작
                startCongestionListener()
            }
        }

        val container = view.findViewById<FrameLayout>(R.id.tmap_container)
        container.addView(tMapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 2) "내 위치로 복귀" 버튼
        val recenterBtn = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.my_location)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.round_button_bg)
            setPadding((12 * resources.displayMetrics.density).toInt())
            contentDescription = "현위치로 이동"
            setOnClickListener {
                followMyLocation = true
                lastLat?.let { lat ->
                    lastLon?.let { lon ->
                        if (isMapReady) {
                            try {
                                tMapView.setCenterPoint(lat, lon)
                                tMapView.setZoomLevel(17)
                                Log.d(TAG, "버튼 클릭: 현 위치로 이동 (lat=$lat, lon=$lon)")
                            } catch (e: Exception) {
                                Log.e(TAG, "버튼으로 위치 이동 실패", e)
                            }
                        }
                    }
                }
            }
        }

        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()
        val btnParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
            rightMargin = margin
            bottomMargin = margin
        }
        container.addView(recenterBtn, btnParams)

        // 3) 지도 터치 시 자동 추적 해제
        tMapView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                followMyLocation = false
                Log.d(TAG, "지도 터치: 자동 추적 해제")
            }
            false
        }

        // 4) Google Location Services 초기화
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L // 1초마다 체크
        ).apply {
            setMinUpdateDistanceMeters(5f)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val lat = location.latitude
                    val lon = location.longitude

                    if (lat == 0.0 || lon == 0.0) return

                    lastLat = lat
                    lastLon = lon

                    // 지도가 준비된 경우에만 위치 업데이트
                    if (isMapReady) {
                        try {
                            // 내 위치 마커 표시
                            tMapView.setLocationPoint(lon, lat)

                            // 첫 위치 받았을 때 자동으로 지도 중심 이동
                            if (isFirstLocation) {
                                isFirstLocation = false
                                tMapView.setCenterPoint(lat, lon)
                                followMyLocation = true
                                Log.d(TAG, "첫 위치 설정 완료")
                            }

                            // 자동 추적 모드일 때 지도 중심 이동
                            if (followMyLocation) {
                                tMapView.setCenterPoint(lat, lon)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "위치 업데이트 실패", e)
                        }
                    }

                    // ✅ Firestore 업로드 조건 체크 (10m 이상 OR 30초 경과)
                    checkAndUploadLocation(lat, lon)
                }
            }
        }

        // 5) 권한 체크 후 위치 업데이트 시작
        if (hasLocationPermission()) {
            startLocationUpdates()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
        }
    }

    /**
     * ✅ 위치 업로드 조건 체크
     * - 10m 이상 이동 OR 30초 경과 시 업로드
     */
    private fun checkAndUploadLocation(lat: Double, lon: Double) {
        val currentTime = System.currentTimeMillis()
        val timeDiff = currentTime - lastUploadTime

        // 거리 체크 (10m 이상 이동했는가?)
        val distanceMoved = lastUploadLat?.let { lastLat ->
            lastUploadLon?.let { lastLon ->
                calculateDistance(lastLat, lastLon, lat, lon)
            }
        } ?: Double.MAX_VALUE

        // 조건: 10m 이상 이동 OR 30초 경과
        val shouldUpload = distanceMoved >= UPLOAD_DISTANCE_THRESHOLD || timeDiff >= UPLOAD_TIME_THRESHOLD

        if (shouldUpload) {
            val userId = auth.currentUser?.uid ?: "anonymous"
            repo.uploadLocation(userId, lat, lon)

            lastUploadLat = lat
            lastUploadLon = lon
            lastUploadTime = currentTime

            Log.d(TAG, "위치 업로드: 이동거리=${distanceMoved.toInt()}m, 경과시간=${timeDiff/1000}초")
        }
    }

    /**
     * 두 지점 간 거리 계산 (미터)
     */
    private fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return earthRadius * c
    }

    /**
     * ✅ 혼잡도 실시간 리스너 시작
     */
    private fun startCongestionListener() {
        if (!isMapReady) {
            Log.w(TAG, "지도가 아직 준비되지 않았습니다.")
            return
        }

        locationListener = repo.listenRecentLocations(minutesAgo = 2) { locations ->
            Log.d(TAG, "혼잡도 업데이트: ${locations.size}개 사용자")
            updateCongestionClusters(locations)
        }
    }

    /**
     * ✅ 혼잡도 클러스터 업데이트 및 지도에 표시
     */
    private fun updateCongestionClusters(locations: List<LocationData>) {
        if (!isMapReady) return

        try {
            // 기존 클러스터 원형 제거
            clusterCircles.forEach { id ->
                try {
                    tMapView.removeTMapCircle(id)
                } catch (e: Exception) {
                    Log.w(TAG, "원형 제거 실패: $id", e)
                }
            }
            clusterCircles.clear()

            // ✅ 더미 데이터 제거: 실제 Firestore 데이터만 사용
            val allLocations = locations

            Log.d(TAG, "실제 사용자 위치 ${allLocations.size}개로 혼잡도 계산")

            // 새로운 클러스터 생성
            val clusters = CongestionCalculator.createClusters(allLocations, radiusMeters = 100.0)

            Log.d(TAG, "생성된 클러스터: ${clusters.size}개")

            clusters.forEachIndexed { index, cluster ->
                drawClusterOnMap(cluster, index)
            }

        } catch (e: Exception) {
            Log.e(TAG, "클러스터 업데이트 실패", e)
        }
    }


    /**
     * ✅ 클러스터를 지도에 원형으로 표시
     */
    private fun drawClusterOnMap(cluster: CongestionCluster, index: Int) {
        try {
            val circleId = "cluster_$index"
            val point = TMapPoint(cluster.centerLat, cluster.centerLon)

            val circle = TMapCircle().apply {
                setId(circleId)
                setCenterPoint(point)
                setRadius(100.0)
                setLineColor(cluster.level.color)
                setAreaColor(cluster.level.color)
                setAreaAlpha(140)  // ✅ 면 투명도 (0~255, 중간 밝기)
                setLineAlpha(220)  // ✅ 선 불투명도 (살짝 투명)
                setCircleWidth(6f) // ✅ 선 굵기 적당히
                setRadiusVisible(false)
            }

            tMapView.addTMapCircle(circle)
            clusterCircles.add(circleId)
            Log.d(TAG, "✅ 혼잡도 원 추가: $circleId")

        } catch (e: Exception) {
            Log.e(TAG, "클러스터 그리기 실패: index=$index", e)
        }
    }


    /**
     * 색상에 투명도 추가
     */
    private fun addAlphaToColor(color: Int, alpha: Float): Int {
        val alphaInt = (alpha * 255).toInt()
        return Color.argb(
            alphaInt,
            Color.red(color),
            Color.green(color),
            Color.blue(color)
        )
    }

    private fun startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.getMainLooper()
            )
            Log.d(TAG, "위치 업데이트 시작")
        } catch (e: Exception) {
            Log.e(TAG, "위치 업데이트 시작 실패", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d(TAG, "위치 업데이트 중지")
        } catch (e: Exception) {
            Log.e(TAG, "위치 업데이트 중지 실패", e)
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        val coarse = ActivityCompat.checkSelfPermission(
            requireContext(),
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        return fine == PackageManager.PERMISSION_GRANTED ||
                coarse == PackageManager.PERMISSION_GRANTED
    }

    override fun onResume() {
        super.onResume()
        if (hasLocationPermission()) {
            startLocationUpdates()
        }

        // 혼잡도 리스너 재시작
        if (isMapReady && locationListener == null) {
            startCongestionListener()
        }
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()

        // ✅ 혼잡도 리스너 해제
        locationListener?.remove()
        locationListener = null

        // 클러스터 제거
        clusterCircles.forEach { id ->
            try {
                tMapView.removeTMapCircle(id)
            } catch (e: Exception) {
                Log.w(TAG, "원형 제거 실패: $id", e)
            }
        }
        clusterCircles.clear()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == REQ_LOCATION &&
            grantResults.isNotEmpty() &&
            grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startLocationUpdates()
        }
    }

    companion object {
        private const val TAG = "MapFragment"
        private const val UPLOAD_DISTANCE_THRESHOLD = 10.0 // 10m
        private const val UPLOAD_TIME_THRESHOLD = 30000L // 30초
    }
}