package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
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
import com.example.capstone.data.LocationRepository
import com.google.firebase.auth.FirebaseAuth


class MapFragment : Fragment(R.layout.fragment_map) {

    private lateinit var tMapView: TMapView

    // Google Location Services
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var locationRequest: LocationRequest

    // 마지막으로 받은 내 위치
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // 지도 시작 시 true로 시작 → 첫 위치에 자동 고정
    private var followMyLocation: Boolean = true

    // 권한 요청 코드
    private val REQ_LOCATION = 1000
    private var isFirstLocation = true
    private var isMapReady = false
    private lateinit var repo: LocationRepository
    private lateinit var auth: FirebaseAuth
    private var lastUploadTime = 0L

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
                Log.d("MapFragment", "TMapView 초기화 완료")

                // 이미 위치를 받았다면 지금 표시
                lastLat?.let { lat ->
                    lastLon?.let { lon ->
                        try {
                            setLocationPoint(lon, lat)
                            if (followMyLocation) {
                                setCenterPoint(lat, lon) // 위도, 경도 순서
                            }
                            Log.d("MapFragment", "지도 초기화 후 위치 설정 완료")
                        } catch (e: Exception) {
                            Log.e("MapFragment", "지도 초기화 후 위치 설정 실패", e)
                        }
                    }
                }
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
                                tMapView.setCenterPoint(lat, lon) // 위도, 경도 순서
                                tMapView.setZoomLevel(17)
                                Log.d("MapFragment", "버튼 클릭: 현 위치로 이동 (lat=$lat, lon=$lon)")
                            } catch (e: Exception) {
                                Log.e("MapFragment", "버튼으로 위치 이동 실패", e)
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
                Log.d("MapFragment", "지도 터치: 자동 추적 해제")
            }
            false
        }

        // 4) Google Location Services 초기화
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireContext())

        locationRequest = LocationRequest.Builder(
            Priority.PRIORITY_HIGH_ACCURACY,
            1000L
        ).apply {
            setMinUpdateDistanceMeters(5f)
            setWaitForAccurateLocation(false)
        }.build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    val lat = location.latitude
                    val lon = location.longitude

                    Log.d("MapFragment", "위치 수신: lat=$lat, lon=$lon, isFirst=$isFirstLocation, follow=$followMyLocation, mapReady=$isMapReady")

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
                                tMapView.setCenterPoint(lat, lon) // 위도, 경도 순서
                                followMyLocation = true
                                Log.d("MapFragment", "첫 위치 설정 완료")
                            }

                            // 자동 추적 모드일 때 지도 중심 이동
                            if (followMyLocation) {
                                tMapView.setCenterPoint(lat, lon) // 위도, 경도 순서
                            }
                        } catch (e: Exception) {
                            Log.e("MapFragment", "위치 업데이트 실패", e)
                        }
                    }

                    // Firestore 업로드 (5초마다)
                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastUploadTime >= 5000) {
                        val userId = auth.currentUser?.uid ?: "anonymous"
                        repo.uploadLocation(userId, lat, lon)
                        lastUploadTime = currentTime
                    }
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
            Log.d("MapFragment", "위치 업데이트 시작")
        } catch (e: Exception) {
            Log.e("MapFragment", "위치 업데이트 시작 실패", e)
        }
    }

    private fun stopLocationUpdates() {
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Log.d("MapFragment", "위치 업데이트 중지")
        } catch (e: Exception) {
            Log.e("MapFragment", "위치 업데이트 중지 실패", e)
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
    }

    override fun onPause() {
        super.onPause()
        stopLocationUpdates()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopLocationUpdates()
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