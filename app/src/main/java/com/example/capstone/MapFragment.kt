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

                // ✅ 테스트: 간단한 빨간 원 1개만 그려보기
                testDrawSimpleCircle()
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

            // ✅ 테스트용 더미 데이터 추가 (실제 배포 시 제거)
            val testLocations = createDummyLocations()
            val allLocations = locations + testLocations

            Log.d(TAG, "실제 사용자: ${locations.size}개, 더미: ${testLocations.size}개, 총: ${allLocations.size}개")

            // 새로운 클러스터 생성
            val clusters = CongestionCalculator.createClusters(allLocations, radiusMeters = 100.0)

            Log.d(TAG, "생성된 클러스터: ${clusters.size}개")

            // ✅ TMapCircle로 시도 (실패하면 Polyline 사용)
            var usePolyline = false

            // 클러스터를 지도에 표시
            clusters.forEachIndexed { index, cluster ->
                if (usePolyline) {
                    // Polyline 방식
                    drawClusterWithPolyline(cluster, index)
                } else {
                    // TMapCircle 방식 (기존)
                    drawClusterOnMap(cluster, index)
                }
            }

            // ✅ 추가: Polyline 방식도 함께 시도 (디버깅용)
            if (clusters.isNotEmpty()) {
                Log.d(TAG, "🟣 Polyline 방식으로도 첫 번째 클러스터 그려보기...")
                drawClusterWithPolyline(clusters[0], 99)  // index 99로 구분
            }

        } catch (e: Exception) {
            Log.e(TAG, "클러스터 업데이트 실패", e)
        }
    }

    /**
     * ✅ Polyline으로 클러스터 그리기 (대안)
     */
    private fun drawClusterWithPolyline(cluster: CongestionCluster, index: Int) {
        try {
            val circleId = "poly_cluster_$index"

            Log.d(TAG, "🟣 Polyline 클러스터 생성: $circleId, 좌표=(${cluster.centerLat}, ${cluster.centerLon})")

            drawCircleWithPolyline(
                cluster.centerLat,
                cluster.centerLon,
                100.0,  // 100m 반경
                circleId,
                cluster.level.color
            )

            clusterCircles.add(circleId)
            Log.d(TAG, "🟣 Polyline 클러스터 표시 완료: $circleId")

        } catch (e: Exception) {
            Log.e(TAG, "🟣 Polyline 클러스터 실패: index=$index", e)
        }
    }

    /**
     * ✅ 테스트용 더미 위치 데이터 생성
     * 실제 배포 시 이 함수와 호출 부분을 제거하세요!
     */
    private fun createDummyLocations(): List<LocationData> {
        // ✅ GPS 위치가 없어도 동작하도록 고정 좌표 사용
        // 서울시청 좌표: 37.5665, 126.9780
        val baseLat = lastLat ?: 37.5665  // GPS 없으면 서울시청
        val baseLon = lastLon ?: 126.9780

        val dummyList = mutableListOf<LocationData>()
        val now = System.currentTimeMillis()

        Log.d(TAG, "더미 데이터 기준 위치: lat=$baseLat, lon=$baseLon")

        // 그룹 1: 현재 위치 근처 50m 이내 3명 -> 노란 원
        dummyList.add(LocationData("dummy_near_1", baseLat + 0.0003, baseLon + 0.0003, now))
        dummyList.add(LocationData("dummy_near_2", baseLat + 0.0002, baseLon - 0.0002, now))
        dummyList.add(LocationData("dummy_near_3", baseLat - 0.0002, baseLon + 0.0001, now))

        // 그룹 2: 200m 북쪽에 6명 -> 빨간 원
        dummyList.add(LocationData("dummy_north_1", baseLat + 0.0018, baseLon, now))
        dummyList.add(LocationData("dummy_north_2", baseLat + 0.0019, baseLon + 0.0001, now))
        dummyList.add(LocationData("dummy_north_3", baseLat + 0.0017, baseLon - 0.0001, now))
        dummyList.add(LocationData("dummy_north_4", baseLat + 0.0018, baseLon + 0.0002, now))
        dummyList.add(LocationData("dummy_north_5", baseLat + 0.0020, baseLon, now))
        dummyList.add(LocationData("dummy_north_6", baseLat + 0.0019, baseLon - 0.0002, now))

        // 그룹 3: 300m 남쪽에 2명 -> 노란 원
        dummyList.add(LocationData("dummy_south_1", baseLat - 0.0027, baseLon + 0.0001, now))
        dummyList.add(LocationData("dummy_south_2", baseLat - 0.0028, baseLon - 0.0001, now))

        // 그룹 4: 400m 동쪽에 4명 -> 노란 원
        dummyList.add(LocationData("dummy_east_1", baseLat + 0.0001, baseLon + 0.0036, now))
        dummyList.add(LocationData("dummy_east_2", baseLat, baseLon + 0.0037, now))
        dummyList.add(LocationData("dummy_east_3", baseLat - 0.0001, baseLon + 0.0035, now))
        dummyList.add(LocationData("dummy_east_4", baseLat + 0.0002, baseLon + 0.0038, now))

        Log.d(TAG, "더미 데이터 생성 완료: ${dummyList.size}개")
        return dummyList
    }

    /**
     * ✅ 클러스터를 지도에 원형으로 표시
     */
    private fun drawClusterOnMap(cluster: CongestionCluster, index: Int) {
        try {
            val circleId = "cluster_$index"
            val point = TMapPoint(cluster.centerLat, cluster.centerLon)

            Log.d(TAG, "원 생성 시작: $circleId, 좌표=(${cluster.centerLat}, ${cluster.centerLon}), 인원=${cluster.userCount}")

            // ✅ 공식 문서 방식대로 수정
            val circle = TMapCircle()
            circle.setId(circleId)
            circle.setCenterPoint(point)
            circle.setRadius(100.0)  // 100m
            circle.setLineColor(cluster.level.color)
            circle.setAreaColor(cluster.level.color)
            circle.setAreaAlpha(200)  // 0-255 범위, 더 불투명하게
            circle.setLineAlpha(255)  // 불투명
            circle.setCircleWidth(10f)  // 더 두껍게
            circle.setRadiusVisible(false)  // 반지름 텍스트 숨김

            Log.d(TAG, "원 속성 설정 완료: radius=100.0, lineColor=${cluster.level.color}, areaColor=${cluster.level.color}")

            Log.d(TAG, "원 객체 생성 완료, 지도에 추가 시도...")

            try {
                tMapView.addTMapCircle(circle)
                Log.d(TAG, "✅ 원 추가 성공: $circleId")
            } catch (e: Exception) {
                Log.e(TAG, "❌ 원 추가 실패: $circleId", e)
                throw e
            }

            clusterCircles.add(circleId)

            Log.d(TAG, "클러스터 표시 완료: ID=$circleId, 사용자=${cluster.userCount}명, 레벨=${cluster.level.displayName}")
        } catch (e: Exception) {
            Log.e(TAG, "클러스터 그리기 실패: index=$index", e)
            e.printStackTrace()
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

    /**
     * ✅ 테스트: 간단한 원 1개만 그려보기
     * 현재 위치(또는 서울시청)에 빨간 원 1개
     */
    private fun testDrawSimpleCircle() {
        try {
            val testLat = lastLat ?: 37.5665
            val testLon = lastLon ?: 126.9780

            Log.d(TAG, "🔴 테스트 원 그리기 시작: lat=$testLat, lon=$testLon")

            try {
                val point = TMapPoint(testLat, testLon)

                // ✅ 공식 API 방식으로 수정
                val circle = TMapCircle()
                circle.setId("test_circle")
                circle.setCenterPoint(point)
                circle.setRadius(200.0)  // 200m
                circle.setLineColor(Color.RED)
                circle.setAreaColor(Color.RED)
                circle.setAreaAlpha(200)  // 0-255 범위
                circle.setLineAlpha(255)  // 불투명
                circle.setCircleWidth(10f)  // 두껍게
                circle.setRadiusVisible(false)

                Log.d(TAG, "🔴 테스트 원 객체 생성 완료, 지도에 추가 시도...")
                tMapView.addTMapCircle(circle)
                Log.d(TAG, "🔴 ✅ 테스트 원(TMapCircle) 추가 성공!")
            } catch (e: Exception) {
                Log.e(TAG, "🔴 ❌ TMapCircle 실패, TMapPolyline으로 시도...", e)
                e.printStackTrace()

                // 방법 2: TMapPolyline으로 원 그리기
                drawCircleWithPolyline(testLat, testLon, 200.0, "test_polyline_circle", Color.RED)
            }

        } catch (e: Exception) {
            Log.e(TAG, "🔴 ❌ 모든 테스트 원 추가 실패!", e)
            e.printStackTrace()
        }
    }

    /**
     * ✅ TMapPolyline을 사용해서 원 그리기 (대안)
     */
    private fun drawCircleWithPolyline(centerLat: Double, centerLon: Double, radiusMeters: Double, id: String, color: Int) {

        Log.d(TAG, "🟣 Polyline으로 원 그리기 시작: $id")

        // 원의 둘레를 따라 점들 생성 (36개 점 = 10도 간격)
        val points = mutableListOf<TMapPoint>()
        val numPoints = 36
        val earthRadius = 6371000.0 // 지구 반경 (미터)

        for (i in 0..numPoints) {
            val angle = (i * 360.0 / numPoints) * Math.PI / 180.0

            // 위도/경도 오프셋 계산
            val dLat = (radiusMeters / earthRadius) * (180.0 / Math.PI)
            val dLon = (radiusMeters / (earthRadius * Math.cos(Math.toRadians(centerLat)))) * (180.0 / Math.PI)

            val pointLat = centerLat + dLat * Math.sin(angle)
            val pointLon = centerLon + dLon * Math.cos(angle)

            points.add(TMapPoint(pointLat, pointLon))
        }
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