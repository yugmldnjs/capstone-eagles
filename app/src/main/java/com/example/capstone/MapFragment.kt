package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.*
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.setPadding
import androidx.fragment.app.Fragment
import com.skt.tmap.TMapGpsManager
import com.skt.tmap.TMapView
import com.example.capstone.data.LocationRepository
import com.google.firebase.auth.FirebaseAuth


class MapFragment : Fragment(R.layout.fragment_map) {

    private lateinit var tMapView: TMapView
    private lateinit var gps: TMapGpsManager

    // 마지막으로 받은 내 위치(버튼 눌러 복귀할 때 사용)
    private var lastLat: Double? = null
    private var lastLon: Double? = null

    // 사용자가 지도를 손으로 움직이면 false, 버튼으로 복귀하면 true
    private var followMyLocation: Boolean = false

    // 권한 요청 코드
    private val REQ_LOCATION = 1000
    private var isFirstLocation = true
    private lateinit var repo: LocationRepository
    private lateinit var auth: FirebaseAuth
    private var lastUploadTime = 0L // 마지막 Firestore 업로드 시각(ms)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        repo = LocationRepository()
        auth = FirebaseAuth.getInstance()


        // 1) 지도 초기화 : 기본은 '자유 이동' 모드 (followMyLocation = false)
        tMapView = TMapView(requireContext()).apply {
            setSKTMapApiKey(BuildConfig.TMAP_API_KEY)
            setZoomLevel(15)
            setIconVisibility(false)   // 처음엔 숨김 상태
            setIconVisibility(true)    // 이제 안전하게 표시
        }

        // fragment_map.xml 안의 컨테이너에 지도 뷰 붙이기
        val container = view.findViewById<FrameLayout>(R.id.tmap_container)
        container.addView(tMapView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 2) "내 위치로 복귀" 버튼(오른쪽 하단) 동적으로 추가
        val recenterBtn = ImageButton(requireContext()).apply {
            setImageResource(R.drawable.my_location) // 적당한 아이콘 사용 (없으면 임시로 camera 아이콘 써도 됩니다)
            background = ContextCompat.getDrawable(requireContext(), R.drawable.round_button_bg) // 둥근 배경(없으면 null 가능)
            setPadding((12 * resources.displayMetrics.density).toInt())
            contentDescription = "현위치로 이동"
            setOnClickListener {
                // 버튼을 누르면 다시 '따라가기' 켜고 지도 중심 복귀
                followMyLocation = true
                lastLat?.let { lat ->
                    lastLon?.let { lon ->
                        tMapView.setCenterPoint(lat, lon) // SDK 좌표 순서가 환경마다 다를 수 있어요. (아래 주석 참고)
                    }
                }
            }
        }

        val btnSize = (48 * resources.displayMetrics.density).toInt()
        val margin = (16 * resources.displayMetrics.density).toInt()
        val btnParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
            // 오른쪽-아래 정렬
            gravity = android.view.Gravity.END or android.view.Gravity.BOTTOM
            rightMargin = margin
            bottomMargin = margin
        }
        container.addView(recenterBtn, btnParams)

        // 3) 지도에 손을 대면 '따라가기' 해제 (자유 이동 유지)
        tMapView.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                followMyLocation = false
            }
            false
        }

        // 4) GPS 초기화
        gps = TMapGpsManager(requireContext()).apply {
            minTime = 1000L       // 1초마다
            minDistance = 5f      // 5m 이동마다
            provider = TMapGpsManager.PROVIDER_GPS
        }

        // 5) 위치 권한 체크 후 시작
        if (hasLocationPermission()) {
            startGps()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                REQ_LOCATION
            )
        }
    }

    private fun startGps() {
        gps.openGps()
        gps.setOnLocationChangeListener { location ->
            val lat = location.latitude
            val lon = location.longitude
            if (lat == 0.0 || lon == 0.0) return@setOnLocationChangeListener

            // 내 위치 갱신
            tMapView.setLocationPoint(lon, lat)

            // ✅ 추가 부분: 앱 처음 실행 시 첫 좌표를 지도 중심으로 이동 이거왜안될까?왜?왜안되지?
            if (isFirstLocation) {
                isFirstLocation = false
                tMapView.setCenterPoint(lon, lat)
            }

            // 기존 코드 그대로 유지
            if (followMyLocation)
                tMapView.setCenterPoint(lon, lat)

            lastLat = lat
            lastLon = lon

            // Firestore 업로드 (5초마다)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastUploadTime >= 5000) {
                val userId = auth.currentUser?.uid ?: "anonymous"
                repo.uploadLocation(userId, lat, lon)
                lastUploadTime = currentTime
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val fine = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        try {
            gps.closeGps()
        } catch (_: Exception) { /* no-op */ }
    }

    // 권한 응답 처리
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
            startGps()
        }
    }
}