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

class MapFragment : Fragment(R.layout.fragment_map), OnMapReadyCallback {

    companion object {
        private const val TAG = "MapFragment"
        private const val REQ_LOCATION = 1000
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

        // 네이버 기본 오버레이 업데이트
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