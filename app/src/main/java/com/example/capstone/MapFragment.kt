package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.google.android.gms.location.*
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.*
import com.naver.maps.map.overlay.CircleOverlay
import com.naver.maps.map.overlay.Marker
import com.naver.maps.map.overlay.OverlayImage
import com.example.capstone.data.LocationRepository
import com.example.capstone.data.LocationData
import com.example.capstone.utils.CongestionCalculator
import com.example.capstone.utils.CongestionCluster
import com.example.capstone.utils.CongestionLevel
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.ListenerRegistration
import kotlin.math.*


class MapFragment : Fragment(R.layout.fragment_map), OnMapReadyCallback {

    private lateinit var naverMap: NaverMap
    private lateinit var mapView: MapView

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

    // ✅ 프로그래밍 방식 카메라 이동 플래그
    private var isProgrammaticMove = false

    // 권한 요청 코드
    private val REQ_LOCATION = 1000
    private var isFirstLocation = true
    private var isMapReady = false
    private lateinit var repo: LocationRepository
    private lateinit var auth: FirebaseAuth

    // ✅ 혼잡도 관련 변수
    private var locationListener: ListenerRegistration? = null
    private val clusterCircles = mutableListOf<CircleOverlay>() // 원형 오버레이 저장
    private val clusterMarkers = mutableListOf<Marker>() // 마커 리스트

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = LocationRepository()
        auth = FirebaseAuth.getInstance()

        // 1) 네이버 맵 초기화
        mapView = view.findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // 2) 커스텀 현위치 버튼
        val container = view.findViewById<FrameLayout>(R.id.map_container)
        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()

        // ✅ 현위치 버튼 (오른쪽 아래)
        val recenterBtn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.round_button_bg)
            setPadding((12 * resources.displayMetrics.density).toInt())
            contentDescription = "현위치로 이동"
            setOnClickListener {
                followMyLocation = true
                lastLat?.let { lat ->
                    lastLon?.let { lon ->
                        if (isMapReady) {
                            try {
                                isProgrammaticMove = true
                                val cameraPosition = CameraPosition(LatLng(lat, lon), 15.0)
                                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                                    .animate(CameraAnimation.Easing)
                                naverMap.moveCamera(cameraUpdate)
                                Log.d(TAG, "버튼 클릭: 현 위치로 이동 (lat=$lat, lon=$lon)")
                            } catch (e: Exception) {
                                Log.e(TAG, "버튼으로 위치 이동 실패", e)
                            }
                        }
                    }
                }
            }
        }

        val recenterParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
            gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
            rightMargin = margin
            bottomMargin = margin
        }
        container.addView(recenterBtn, recenterParams)

        // 3) Google Location Services 초기화
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
                            updateMyLocationMarker(lat, lon)

                            // 첫 위치 받았을 때 자동으로 지도 중심 이동
                            if (isFirstLocation) {
                                isFirstLocation = false
                                isProgrammaticMove = true
                                val cameraPosition = CameraPosition(LatLng(lat, lon), 15.0)
                                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                                naverMap.moveCamera(cameraUpdate)
                                followMyLocation = true
                                Log.d(TAG, "첫 위치 설정 완료")
                            }

                            // 자동 추적 모드일 때 지도 중심 이동
                            if (followMyLocation) {
                                isProgrammaticMove = true
                                val cameraUpdate = CameraUpdate.scrollTo(LatLng(lat, lon))
                                    .animate(CameraAnimation.Easing)
                                naverMap.moveCamera(cameraUpdate)
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

        // 4) 권한 체크 후 위치 업데이트 시작
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

    override fun onMapReady(map: NaverMap) {
        naverMap = map
        isMapReady = true
        Log.d(TAG, "NaverMap 초기화 완료")

        // 지도 설정
        naverMap.apply {
            // 줌 레벨 설정
            minZoom = 5.0
            maxZoom = 18.0

            // ✅ 네이버 지도 기본 현위치 오버레이 활성화
            locationOverlay.isVisible = true

            // 초기 카메라 위치 (서울시청)
            val defaultLat = 37.5665
            val defaultLon = 126.9780

            val targetLat = lastLat ?: defaultLat
            val targetLon = lastLon ?: defaultLon

            // ✅ CameraPosition 사용
            isProgrammaticMove = true
            val cameraPosition = CameraPosition(LatLng(targetLat, targetLon), 15.0)
            val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
            moveCamera(cameraUpdate)

            // ✅ UI 설정
            uiSettings.apply {
                isCompassEnabled = true // 기본 나침반 사용
                isScaleBarEnabled = true // 축척바
                isZoomControlEnabled = true // 줌 컨트롤
                isLocationButtonEnabled = false // 커스텀 현위치 버튼 사용
            }

            // ✅ 지도 터치 시 자동 추적 해제
            addOnCameraChangeListener { _, _ ->
                // 프로그래밍 방식 이동이 아니면 사용자가 터치한 것
                if (!isProgrammaticMove && followMyLocation) {
                    followMyLocation = false
                    Log.d(TAG, "지도 터치: 자동 추적 해제")
                }
                isProgrammaticMove = false
            }
        }

        // 내 위치가 있으면 오버레이 위치 설정
        lastLat?.let { lat ->
            lastLon?.let { lon ->
                updateMyLocationMarker(lat, lon)
                if (followMyLocation) {
                    isProgrammaticMove = true
                    val cameraUpdate = CameraUpdate.scrollTo(LatLng(lat, lon))
                    naverMap.moveCamera(cameraUpdate)
                }
                Log.d(TAG, "지도 초기화 후 위치 설정 완료")
            }
        }

        // ✅ 지도 준비 완료 후 혼잡도 리스너 시작
        startCongestionListener()
    }

    /**
     * ✅ 네이버 지도 기본 현위치 오버레이 업데이트
     */
    private fun updateMyLocationMarker(lat: Double, lon: Double) {
        try {
            naverMap.locationOverlay.apply {
                position = LatLng(lat, lon)
                isVisible = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "내 위치 오버레이 업데이트 실패", e)
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

            // ========== 🔴 더미 데이터 추가 시작 (테스트용 - 나중에 삭제) ==========
            val dummyLocations = generateDummyLocations()
            val allLocations = locations + dummyLocations
            Log.d(TAG, "더미 데이터 포함: ${allLocations.size}개 (실제: ${locations.size}, 더미: ${dummyLocations.size})")
            updateCongestionClusters(allLocations)
            // ========== 🔴 더미 데이터 추가 끝 ==========

            // 실제 운영 시 사용할 코드 (위 3줄 삭제 후 주석 해제)
            // updateCongestionClusters(locations)
        }
    }

    /**
     * ========== 🔴 더미 데이터 생성 함수 (테스트용 - 나중에 삭제) ==========
     * 전국 주요 도시에 골고루 분포된 더미 데이터 생성
     */
    private fun generateDummyLocations(): List<LocationData> {
        val dummyData = mutableListOf<LocationData>()
        val currentTime = System.currentTimeMillis()

        // 서울 (5개 클러스터) - 여유2, 보통2, 혼잡1
        addDummyCluster(dummyData, 37.5665, 126.9780, 15, "seoul", currentTime)      // 시청 - 보통
        addDummyCluster(dummyData, 37.5796, 126.9770, 28, "gangnam", currentTime)   // 강남 - 혼잡
        addDummyCluster(dummyData, 37.5511, 126.9882, 8, "dongdaemun", currentTime) // 동대문 - 여유
        addDummyCluster(dummyData, 37.5547, 126.9707, 32, "myeongdong", currentTime)// 명동 - 혼잡
        addDummyCluster(dummyData, 37.5133, 127.1028, 12, "jamsil", currentTime)    // 잠실 - 보통

        // 부산 (4개 클러스터) - 여유1, 보통2, 혼잡1
        addDummyCluster(dummyData, 35.1796, 129.0756, 26, "haeundae", currentTime)  // 해운대 - 혼잡
        addDummyCluster(dummyData, 35.1028, 129.0403, 14, "seomyeon", currentTime)  // 서면 - 보통
        addDummyCluster(dummyData, 35.0979, 129.0361, 18, "nampo", currentTime)     // 남포동 - 보통
        addDummyCluster(dummyData, 35.1588, 129.1603, 7, "gwangan", currentTime)    // 광안리 - 여유

        // 대구 (3개 클러스터) - 여유1, 보통1, 혼잡1
        addDummyCluster(dummyData, 35.8714, 128.6014, 16, "dongseong", currentTime) // 동성로 - 보통
        addDummyCluster(dummyData, 35.8563, 128.5942, 29, "banwoldang", currentTime)// 반월당 - 혼잡
        addDummyCluster(dummyData, 35.8242, 128.5618, 9, "duryu", currentTime)      // 두류 - 여유

        // 인천 (3개 클러스터) - 여유1, 보통1, 혼잡1
        addDummyCluster(dummyData, 37.4563, 126.7052, 25, "bupyeong", currentTime)  // 부평 - 혼잡
        addDummyCluster(dummyData, 37.4748, 126.6216, 13, "songdo", currentTime)    // 송도 - 보통
        addDummyCluster(dummyData, 37.4532, 126.7318, 8, "juan", currentTime)       // 주안 - 여유

        // 광주 (3개 클러스터) - 여유1, 보통1, 혼잡1
        addDummyCluster(dummyData, 35.1595, 126.8526, 30, "chungjang", currentTime) // 충장로 - 혼잡
        addDummyCluster(dummyData, 35.1470, 126.9216, 11, "suwan", currentTime)     // 수완 - 보통
        addDummyCluster(dummyData, 35.1260, 126.9153, 6, "sangmu", currentTime)     // 상무 - 여유

        // 대전 (3개 클러스터) - 여유1, 보통1, 혼잡1
        addDummyCluster(dummyData, 36.3504, 127.3845, 14, "dunsan", currentTime)    // 둔산 - 보통
        addDummyCluster(dummyData, 36.3273, 127.4288, 27, "yuseong", currentTime)   // 유성 - 혼잡
        addDummyCluster(dummyData, 36.3286, 127.4296, 9, "eunhaeng", currentTime)   // 은행 - 여유

        // 울산 (2개 클러스터) - 보통1, 혼잡1
        addDummyCluster(dummyData, 35.5384, 129.3114, 26, "samsan", currentTime)    // 삼산 - 혼잡
        addDummyCluster(dummyData, 35.5666, 129.3313, 12, "dal", currentTime)       // 달동 - 보통

        // 제주 (2개 클러스터) - 보통1, 여유1
        addDummyCluster(dummyData, 33.4996, 126.5312, 17, "jeju", currentTime)      // 제주시 - 보통
        addDummyCluster(dummyData, 33.2541, 126.5601, 7, "seogwipo", currentTime)   // 서귀포 - 여유

        Log.d(TAG, "🔴 더미 데이터 생성 완료: ${dummyData.size}개 위치")
        return dummyData
    }

    /**
     * ========== 🔴 더미 클러스터 생성 헬퍼 함수 (테스트용 - 나중에 삭제) ==========
     */
    private fun addDummyCluster(
        list: MutableList<LocationData>,
        centerLat: Double,
        centerLon: Double,
        userCount: Int,
        prefix: String,
        timestamp: Long
    ) {
        // 중심점 주변에 사용자들을 랜덤하게 분포
        for (i in 0 until userCount) {
            // 반경 50m 내에 랜덤 분포
            val angle = Math.random() * 2 * Math.PI
            val distance = Math.random() * 50.0 // 0~50m

            val deltaLat = (distance * cos(angle)) / 111320.0 // 위도 1도 = 약 111.32km
            val deltaLon = (distance * sin(angle)) / (111320.0 * cos(Math.toRadians(centerLat)))

            list.add(
                LocationData(
                    userId = "dummy_${prefix}_$i",
                    latitude = centerLat + deltaLat,
                    longitude = centerLon + deltaLon,
                    timestamp = timestamp
                )
            )
        }
    }
    // ========== 🔴 더미 데이터 관련 함수 끝 ==========

    /**
     * ✅ 혼잡도 클러스터 업데이트 및 지도에 표시
     */
    private fun updateCongestionClusters(locations: List<LocationData>) {
        if (!isMapReady) return

        try {
            // 기존 오버레이 제거
            clearAllOverlays()

            val allLocations = locations
            Log.d(TAG, "실제 사용자 위치 ${allLocations.size}개로 혼잡도 계산")

            // 클러스터 생성
            val clusters = CongestionCalculator.createClusters(allLocations, radiusMeters = 100.0)
            Log.d(TAG, "생성된 클러스터: ${clusters.size}개")

            // 각 클러스터를 원형과 마커 동시에 표시
            clusters.forEachIndexed { index, cluster ->
                // 1~4명: 표시 안 함
                if (cluster.userCount < 5) return@forEachIndexed

                drawClusterOnMap(cluster, index)
            }

        } catch (e: Exception) {
            Log.e(TAG, "클러스터 업데이트 실패", e)
        }
    }

    /**
     * ✅ 클러스터를 원형 + 중앙 마커로 동시에 표시
     */
    private fun drawClusterOnMap(cluster: CongestionCluster, index: Int) {
        try {
            // 1. 원형 오버레이 생성
            val circle = CircleOverlay().apply {
                center = LatLng(cluster.centerLat, cluster.centerLon)
                radius = 150.0
                color = addAlphaToColor(cluster.level.color, 0.55f)
                outlineColor = addAlphaToColor(cluster.level.color, 0.86f)
                outlineWidth = 6
                map = naverMap
            }
            clusterCircles.add(circle)

            // 2. 중앙에 숫자 마커 생성
            val marker = Marker().apply {
                position = LatLng(cluster.centerLat, cluster.centerLon)
                icon = createMarkerIcon(cluster.userCount, cluster.level)
                width = 80
                height = 80
                map = naverMap

                // ✅ 클릭 시 해당 위치로 줌인
                setOnClickListener {
                    isProgrammaticMove = true
                    val cameraPosition = CameraPosition(
                        LatLng(cluster.centerLat, cluster.centerLon),
                        15.0  // 줌 레벨 (원하는 대로 조정)
                    )
                    val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                        .animate(CameraAnimation.Easing)
                    naverMap.moveCamera(cameraUpdate)
                    true // 이벤트 소비
                }
            }
            clusterMarkers.add(marker)

            Log.d(TAG, "✅ 클러스터 표시: cluster_$index (${cluster.userCount}명, ${cluster.level.displayName})")

        } catch (e: Exception) {
            Log.e(TAG, "클러스터 그리기 실패: index=$index", e)
        }
    }

    /**
     * ✅ 마커 내부에 숫자가 있는 커스텀 아이콘 생성
     */
    private fun createMarkerIcon(count: Int, level: CongestionLevel): OverlayImage {
        val size = 80 // dp
        val density = resources.displayMetrics.density
        val pixelSize = (size * density).toInt()

        val bitmap = android.graphics.Bitmap.createBitmap(pixelSize, pixelSize, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)

        // 원형 배경 그리기
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.FILL
            color = level.color
        }

        val centerX = pixelSize / 2f
        val centerY = pixelSize / 2f
        val radius = pixelSize / 2.5f

        canvas.drawCircle(centerX, centerY, radius, paint)

        // 테두리 그리기
        val strokePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            style = android.graphics.Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 4f * density
        }
        canvas.drawCircle(centerX, centerY, radius, strokePaint)

        // 숫자 그리기
        val textPaint = android.graphics.Paint().apply {
            isAntiAlias = true
            color = Color.WHITE
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

    /**
     * ✅ 모든 오버레이 제거 (원형 + 마커)
     */
    private fun clearAllOverlays() {
        // 원형 오버레이 제거
        clusterCircles.forEach { circle ->
            try {
                circle.map = null
            } catch (e: Exception) {
                Log.w(TAG, "원형 제거 실패", e)
            }
        }
        clusterCircles.clear()

        // 마커 제거
        clusterMarkers.forEach { marker ->
            try {
                marker.map = null
            } catch (e: Exception) {
                Log.w(TAG, "마커 제거 실패", e)
            }
        }
        clusterMarkers.clear()
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

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

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
        mapView.onPause()
        stopLocationUpdates()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        mapView.onSaveInstanceState(outState)
    }

    override fun onStop() {
        super.onStop()
        mapView.onStop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()

        // ✅ 혼잡도 리스너 해제
        locationListener?.remove()
        locationListener = null

        // ✅ 모든 오버레이 제거
        clearAllOverlays()

        mapView.onDestroy()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        mapView.onLowMemory()
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