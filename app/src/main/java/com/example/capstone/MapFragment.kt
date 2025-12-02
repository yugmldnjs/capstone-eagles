package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.*
import com.example.capstone.data.LocationRepository
import com.google.firebase.auth.FirebaseAuth
import com.example.capstone.data.PotholeRepository
import com.example.capstone.map.CongestionOverlayManager
import com.example.capstone.map.MapLocationManager
import com.example.capstone.map.PotholeOverlayManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import com.example.capstone.utils.LocationUtils
import kotlin.math.abs

class MapFragment : Fragment(R.layout.fragment_map), OnMapReadyCallback {

    companion object {
        private const val TAG = "MapFragment"
        private const val REQ_LOCATION = 1000

        // ✅ 포트홀 경고 조건
        private const val POTHOLE_ALERT_DISTANCE_METERS = 20.0   // 거리 20m
        private const val POTHOLE_ALERT_ANGLE_DEG = 60.0         // 진행 방향 ±60도 안쪽만
        private const val POTHOLE_ALERT_INTERVAL_MS = 10_000L    // 최소 10초 간격
    }

    private lateinit var naverMap: NaverMap
    private lateinit var mapView: MapView

    private lateinit var locationManager: MapLocationManager
    private lateinit var congestionManager: CongestionOverlayManager
    private lateinit var potholeManager: PotholeOverlayManager

    private var followMyLocation: Boolean = true
    private var isProgrammaticMove: Boolean = false
    private var isFirstLocation: Boolean = true
    private var isMapReady: Boolean = false

    private lateinit var repo: LocationRepository
    private lateinit var potholeRepo: PotholeRepository
    private lateinit var auth: FirebaseAuth

    private var showCongestion: Boolean = true
    private var showPotholeMarkers: Boolean = true

    // ✅ TTS (포트홀 경고용)
    private var tts: TextToSpeech? = null

    // 최근 경고 시간 + 이미 경고한 포트홀 ID
    private var lastPotholeAlertTime: Long = 0L
    private val alertedPotholeIds = mutableSetOf<String>()

    // 진행 방향 계산용 (직전 위치)
    private var prevLatForHeading: Double? = null
    private var prevLonForHeading: Double? = null
    private var lastHeadingDeg: Double? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = LocationRepository()
        auth = FirebaseAuth.getInstance()
        potholeRepo = PotholeRepository()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        showCongestion = prefs.getBoolean("show_congestion", true)
        showPotholeMarkers = prefs.getBoolean("show_pothole_markers", true)

        // 1) 네이버 맵
        mapView = view.findViewById(R.id.map_view)
        mapView.onCreate(savedInstanceState)
        mapView.getMapAsync(this)

        // 2) 커스텀 현위치 버튼
        setupRecenterButton(view)  // 아래에 함수 하나 새로 뺄 것

        // 3) LocationManager 생성
        locationManager = MapLocationManager(
            context = requireContext(),
            repo = repo,
            auth = auth
        ) { lat, lon ->
            onLocationUpdatedFromManager(lat, lon)
        }

        // 4) ✅ TTS 초기화
        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.KOREAN
            } else {
                Log.e(TAG, "TTS 초기화 실패: status=$status")
            }
        }
    }
    private fun setupRecenterButton(rootView: View) {
        val container = rootView.findViewById<FrameLayout>(R.id.map_container)
        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()

        val recenterBtn = ImageButton(requireContext()).apply {
            setImageResource(android.R.drawable.ic_menu_mylocation)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.round_button_bg)
            setPadding((12 * resources.displayMetrics.density).toInt())
            contentDescription = "현위치로 이동"

            setOnClickListener {
                followMyLocation = true

                val lat = locationManager.lastLat
                val lon = locationManager.lastLon

                if (lat != null && lon != null && isMapReady) {
                    try {
                        isProgrammaticMove = true
                        val cameraPosition = CameraPosition(LatLng(lat, lon), 15.0)
                        val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                            .animate(CameraAnimation.Easing)
                        naverMap.moveCamera(cameraUpdate)
                    } catch (e: Exception) {
                        Log.e(TAG, "버튼으로 위치 이동 실패", e)
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
    }
    private fun onLocationUpdatedFromManager(lat: Double, lon: Double) {
        if (!isMapReady) return

        // ✅ 1) 진행 방향(heading) 업데이트 (직전 위치 기준)
        val prevLat = prevLatForHeading
        val prevLon = prevLonForHeading
        if (prevLat != null && prevLon != null) {
            val moved = LocationUtils.calculateDistance(prevLat, prevLon, lat, lon)
            if (moved >= 1.0) {  // 1m 이상 움직였을 때만 방향 갱신
                lastHeadingDeg = LocationUtils.calculateBearing(prevLat, prevLon, lat, lon)
            }
        }
        prevLatForHeading = lat
        prevLonForHeading = lon

        // 2) 네이버 기본 오버레이 업데이트
        updateMyLocationMarker(lat, lon)

        if (isFirstLocation) {
            isFirstLocation = false
            followMyLocation = true

            isProgrammaticMove = true
            val cameraPosition = CameraPosition(LatLng(lat, lon), 15.0)
            val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
            naverMap.moveCamera(cameraUpdate)
        }

        if (followMyLocation) {
            isProgrammaticMove = true
            val cameraUpdate = CameraUpdate.scrollTo(LatLng(lat, lon))
                .animate(CameraAnimation.Easing)
            naverMap.moveCamera(cameraUpdate)
        }

        // ✅ 3) 포트홀 TTS 경고 체크
        checkPotholeAlertTts(lat, lon)
    }

    // ✅ 현재 주행 방향 앞 20m 안에 포트홀이 있는지 확인하고 TTS 재생
    private fun checkPotholeAlertTts(lat: Double, lon: Double) {
        val ttsEngine = tts ?: return
        if (!this::potholeManager.isInitialized) return

        val now = System.currentTimeMillis()
        if (now - lastPotholeAlertTime < POTHOLE_ALERT_INTERVAL_MS) return

        val heading = lastHeadingDeg
        val potholes = potholeManager.getCurrentPotholes()
        if (potholes.isEmpty()) return

        var target: com.example.capstone.data.PotholeData? = null
        var minDist = Double.MAX_VALUE

        for (p in potholes) {
            val dist = LocationUtils.calculateDistance(
                lat, lon,
                p.latitude, p.longitude
            )
            if (dist > POTHOLE_ALERT_DISTANCE_METERS) continue

            // 진행 방향 기준 앞쪽인지 확인 (±60도)
            if (heading != null) {
                val bearingToPin = LocationUtils.calculateBearing(
                    lat, lon,
                    p.latitude, p.longitude
                )
                var diff = abs(bearingToPin - heading)
                if (diff > 180.0) diff = 360.0 - diff
                if (diff > POTHOLE_ALERT_ANGLE_DEG) continue
            }

            // 같은 포트홀에 대해 한 번만 경고
            val id = p.id
            if (id != null && alertedPotholeIds.contains(id)) {
                continue
            }

            if (dist < minDist) {
                minDist = dist
                target = p
            }
        }

        if (target != null) {
            lastPotholeAlertTime = now
            target.id?.let { alertedPotholeIds.add(it) }
            speakPotholeWarning()
        }
    }

    private fun speakPotholeWarning() {
        val ttsEngine = tts ?: return
        ttsEngine.speak(
            "포트홀을 주의하세요",
            TextToSpeech.QUEUE_ADD,
            null,
            "POTHOLE_WARNING"
        )
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
            val targetLat = locationManager.lastLat ?: defaultLat
            val targetLon = locationManager.lastLon ?: defaultLon

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

        // 현재 위치가 이미 있으면 오버레이/카메라 맞춰주기
        locationManager.lastLat?.let { lat ->
            locationManager.lastLon?.let { lon ->
                updateMyLocationMarker(lat, lon)
                if (followMyLocation) {
                    isProgrammaticMove = true
                    val cameraUpdate = CameraUpdate.scrollTo(LatLng(lat, lon))
                    naverMap.moveCamera(cameraUpdate)
                }
                Log.d(TAG, "지도 초기화 후 위치 설정 완료")
            }
        }

        // 혼잡도 매니저
        congestionManager = CongestionOverlayManager(
            naverMap = naverMap,
            repo = repo
        ) { lat, lon, zoom ->
            isProgrammaticMove = true
            val cameraPosition = CameraPosition(LatLng(lat, lon), zoom)
            val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                .animate(CameraAnimation.Easing)
            naverMap.moveCamera(cameraUpdate)
        }
        congestionManager.showCongestion = showCongestion
        congestionManager.start()

        // 포트홀 매니저
        potholeManager = PotholeOverlayManager(
            naverMap = naverMap,
            potholeRepo = potholeRepo
        ) { lat, lon, zoom ->
            isProgrammaticMove = true
            val cameraPosition = CameraPosition(LatLng(lat, lon), zoom)
            val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                .animate(CameraAnimation.Easing)
            naverMap.moveCamera(cameraUpdate)
        }
        potholeManager.showPotholeMarkers = showPotholeMarkers
        potholeManager.start()
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
     * 모델/추적기가 "포트홀 확정" 신호를 줄 때 호출.
     * @return 이번 호출로 실제 새로운 포트홀 핀이 추가되었으면 true
     */
    fun addPotholeFromCurrentLocationFromModel(): Boolean {
        // 0) locationManager 준비 여부 체크 (lateinit 보호)
        if (!this::locationManager.isInitialized) {
            Log.d(TAG, "addPotholeFromCurrentLocationFromModel: locationManager 미초기화, 무시")
            return false
        }

        // 1) 지도 / 포트홀 매니저 준비 여부 체크
        if (!isMapReady || !this::potholeManager.isInitialized) {
            Log.d(TAG, "addPotholeFromCurrentLocationFromModel: 지도 또는 potholeManager 준비 안됨, 무시")
            return false
        }

        // 2) 위치 확인
        val lat = locationManager.lastLat
        val lon = locationManager.lastLon

        if (lat == null || lon == null) {
            Log.d(TAG, "addPotholeFromCurrentLocationFromModel: 위치 정보 없음, 무시")
            return false
        }

        // 3) 실제 포트홀 추가 / 중복 여부는 매니저가 판단
        return potholeManager.addPotholeFromLocation(lat, lon)
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

    private fun startLocationUpdates() {
        if (!hasLocationPermission()) {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_LOCATION
            )
            return
        }
        locationManager.start()
    }

    private fun stopLocationUpdates() {
        locationManager.stop()
    }

    override fun onStart() {
        super.onStart()
        mapView.onStart()
    }

    override fun onResume() {
        super.onResume()
        mapView.onResume()

        val prefs = PreferenceManager.getDefaultSharedPreferences(requireContext())
        showCongestion = prefs.getBoolean("show_congestion", true)
        showPotholeMarkers = prefs.getBoolean("show_pothole_markers", true)

        if (hasLocationPermission()) {
            startLocationUpdates()
        }

        if (isMapReady) {
            congestionManager.showCongestion = showCongestion
            potholeManager.showPotholeMarkers = showPotholeMarkers
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

        // 매니저 정리
        if (this::congestionManager.isInitialized) {
            congestionManager.stop()
        }
        if (this::potholeManager.isInitialized) {
            potholeManager.stop()
        }

        // ✅ TTS 자원 해제
        tts?.stop()
        tts?.shutdown()
        tts = null
        alertedPotholeIds.clear()
        prevLatForHeading = null
        prevLonForHeading = null
        lastHeadingDeg = null

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
}