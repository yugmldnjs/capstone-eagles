package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.FrameLayout
import androidx.core.app.ActivityCompat
import androidx.fragment.app.Fragment
import com.skt.tmap.TMapGpsManager
import com.skt.tmap.TMapView

class MapFragment : Fragment(R.layout.fragment_map) {

    private lateinit var tMapView: TMapView
    private lateinit var gps: TMapGpsManager

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 1️⃣ 지도 초기화
        tMapView = TMapView(requireContext()).apply {
            setSKTMapApiKey(BuildConfig.TMAP_API_KEY)
            setZoomLevel(15)
            setIconVisibility(true) // 기본 내 위치 아이콘 표시
        }

        val container = view.findViewById<FrameLayout>(R.id.tmap_container)
        container.addView(tMapView)

        // 2️⃣ GPS 초기화
        gps = TMapGpsManager(requireContext()).apply {
            minTime = 1000L
            minDistance = 5f
            provider = TMapGpsManager.PROVIDER_GPS
        }

        // 3️⃣ 위치 권한 확인 후 GPS 시작
        if (ActivityCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            gps.openGps()
            setGpsListener()
        } else {
            ActivityCompat.requestPermissions(
                requireActivity(),
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                1000
            )
        }
    }

    // 🔸 위치 변경 콜백 (기본 아이콘 사용)
    private fun setGpsListener() {
        gps.setOnLocationChangeListener { location ->
            val lat = location.latitude
            val lon = location.longitude

            Log.d("MapFragment", "위치 업데이트: lat=$lat, lon=$lon")

            // 현위치 아이콘 갱신 + 지도 중심 이동 (lat, lon 순서로)
            tMapView.setLocationPoint(lat, lon)
            tMapView.setCenterPoint(lat, lon)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        gps.closeGps() // 위치 탐색 종료
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == 1000 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            // 권한이 허용되면 GPS 다시 시작
            gps.openGps()
            setGpsListener()
        } else {
            Log.e("MapFragment", "위치 권한이 거부되었습니다.")
        }
    }
}