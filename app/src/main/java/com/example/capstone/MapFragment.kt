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
import com.example.capstone.data.PotholeData
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.widget.TextView
import android.widget.Button
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import android.content.Intent
import android.net.Uri
import com.example.capstone.BuildConfig
import android.widget.ImageView
import com.bumptech.glide.Glide
import android.graphics.Bitmap

class MapFragment : Fragment(R.layout.fragment_map), OnMapReadyCallback {

    companion object {
        private const val TAG = "MapFragment"
        private const val REQ_LOCATION = 1000

        // ✅ 포트홀 경고 조건
        private const val POTHOLE_ALERT_DISTANCE_METERS = 20.0   // 거리 20m
        private const val POTHOLE_ALERT_ANGLE_DEG = 60.0         // 진행 방향 ±60도 안쪽만
        private const val POTHOLE_ALERT_INTERVAL_MS = 10_000L    // 최소 10초 간격
    }

    private val httpClient by lazy { OkHttpClient() }

    // 역지오코딩 결과 보관용
    private data class PotholeAddressInfo(
        val fullAddress: String,
        val area1: String?,   // 시·도 (예: 광주광역시)
        val area2: String?,   // 시·군·구 (예: 서구)
        val area3: String?    // 읍·면·동
    )

    // 지자체(청) 정보
    private data class LocalGovInfo(
        val name: String,     // 예: "광주광역시 서구청"
        val phone: String     // 예: "062-360-7114"
    )

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
            context = requireContext(),
            naverMap = naverMap,
            potholeRepo = potholeRepo,
            onFocusCamera = { lat, lon, zoom ->
                isProgrammaticMove = true
                val cameraPosition = CameraPosition(LatLng(lat, lon), zoom)
                val cameraUpdate = CameraUpdate.toCameraPosition(cameraPosition)
                    .animate(CameraAnimation.Easing)
                naverMap.moveCamera(cameraUpdate)
            },
            onReportClick = { pothole ->
                // 여기서 바텀 시트 열기
                showPotholeReportBottomSheet(pothole)
            }
        )
        potholeManager.showPotholeMarkers = showPotholeMarkers
        potholeManager.start()

        naverMap.setOnMapClickListener { _, _ ->
            potholeManager.closeInfoWindow()
        }
    }

    private fun showPotholeReportBottomSheet(pothole: PotholeData) {
        val dialog = BottomSheetDialog(requireContext())
        val view = layoutInflater.inflate(R.layout.bottomsheet_pothole_report, null)

        val tvLocation = view.findViewById<TextView>(R.id.tv_pothole_info)
        val tvOffice = view.findViewById<TextView>(R.id.tv_office_info)
        val btnCall = view.findViewById<Button>(R.id.btn_call_office)
        val btnSafetyApp = view.findViewById<Button>(R.id.btn_open_safety_app)

        val ivPotholePhoto = view.findViewById<ImageView>(R.id.iv_pothole_photo)

        // 기본 문구
        tvLocation.text = "포트홀 위치: 주소를 불러오는 중..."
        tvOffice.text = "관할 지자체: 확인 중..."
        btnCall.isEnabled = false

        // ✅ 사진 표시 로직
        if (pothole.imageUrl.isNullOrBlank()) {
            ivPotholePhoto.visibility = View.GONE
        } else {
            ivPotholePhoto.visibility = View.VISIBLE
            Glide.with(view)
                .load(pothole.imageUrl)
                .placeholder(R.drawable.loading)   // 이미 있는 걸 재사용해도 되고, 새 placeholder 만들어도 됨
                .error(R.drawable.loading)
                .into(ivPotholePhoto)
        }

        // 위경도 → 주소 + 행정구역 정보 가져오기
        fetchAddressForPothole(pothole) { info ->
            if (info == null) {
                tvLocation.text = "포트홀 위치: 주소를 불러오지 못했습니다."
                tvOffice.text = "관할 지자체: 확인 불가 (근처 지자체로 문의해주세요)"

                // 주소가 없어도 최소한 120으로는 연결
                btnCall.setOnClickListener {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:120")
                    }
                    startActivity(intent)
                    dialog.dismiss()
                }
                btnCall.isEnabled = true
                return@fetchAddressForPothole
            }

            // 지자체 자동 선택
            val gov = getLocalGovernmentInfo(info.area1, info.area2)

            tvLocation.text = "포트홀 위치: ${info.fullAddress}"
            tvOffice.text = "관할 지자체: ${gov.name} (${gov.phone})"

            btnCall.setOnClickListener {
                val intent = Intent(Intent.ACTION_DIAL).apply {
                    data = Uri.parse("tel:${gov.phone}")
                }
                startActivity(intent)
                dialog.dismiss()
            }
            btnCall.isEnabled = true
        }

        // 안전신문고 앱 / 플레이스토어로 이동
        btnSafetyApp.setOnClickListener {
            val packageName = "kr.go.safepeople"  // 안전신문고 앱 패키지명
            val pm = requireContext().packageManager
            val launchIntent = pm.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                // 앱이 설치되어 있으면 바로 실행
                startActivity(launchIntent)
            } else {
                // 설치 안 되어 있으면 플레이스토어 → 안 되면 웹스토어
                try {
                    val playStoreIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("market://details?id=$packageName")
                        setPackage("com.android.vending")
                    }
                    startActivity(playStoreIntent)
                } catch (e: Exception) {
                    val webIntent = Intent(Intent.ACTION_VIEW).apply {
                        data = Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                    }
                    startActivity(webIntent)
                }
            }

            dialog.dismiss()
        }

        dialog.setContentView(view)
        dialog.show()
    }

    private fun postAddressInfoResult(
        info: PotholeAddressInfo?,
        onResult: (PotholeAddressInfo?) -> Unit
    ) {
        if (!isAdded) return
        requireActivity().runOnUiThread {
            onResult(info)
        }
    }

    private fun getLocalGovernmentInfo(
        area1: String?,
        area2: String?
    ): LocalGovInfo {
        val a1 = area1 ?: ""
        val a2 = area2 ?: ""

        // 🔹 광주 5개 구는 실제 대표전화로 매핑
        return when {
            a1.contains("광주") && a2.contains("서구") ->
                LocalGovInfo("광주광역시 서구청", "062-360-7114")  // 대표전화

            a1.contains("광주") && a2.contains("북구") ->
                LocalGovInfo("광주광역시 북구청", "062-410-6794")  // 대표전화

            a1.contains("광주") && a2.contains("동구") ->
                LocalGovInfo("광주광역시 동구청", "062-608-2114")  // 대표전화

            a1.contains("광주") && a2.contains("남구") ->
                LocalGovInfo("광주광역시 남구청", "062-651-9020")  // 대표전화

            a1.contains("광주") && a2.contains("광산구") ->
                LocalGovInfo("광주광역시 광산구청", "062-960-8114")  // 대표전화

            else -> {
                // 그 외 지역은 일단 "OOO청 / 120" 으로 처리 (나중에 필요 지역만 추가)
                val regionName = when {
                    a1.isNotBlank() && a2.isNotBlank() -> "$a1 $a2 청"
                    a2.isNotBlank() -> "$a2 청"
                    a1.isNotBlank() -> "$a1 청"
                    else -> "관할 지자체"
                }
                // TODO: 자주 사용하는 지역은 실제 대표번호로 차근차근 추가
                LocalGovInfo(regionName, "120")
            }
        }
    }

    private fun fetchAddressForPothole(
        pothole: PotholeData,
        onResult: (PotholeAddressInfo?) -> Unit
    ) {
        val lat = pothole.latitude
        val lon = pothole.longitude

        // 네이버 Reverse Geocoding 은 x=경도, y=위도
        val coords = "$lon,$lat"

        val url =
            "https://maps.apigw.ntruss.com/map-reversegeocode/v2/gc" +
                    "?coords=$coords" +
                    "&orders=roadaddr,addr" +
                    "&output=json" +
                    "&request=coordsToaddr" +
                    "&sourcecrs=epsg:4326"

        val request = okhttp3.Request.Builder()
            .url(url)
            .addHeader("X-NCP-APIGW-API-KEY-ID", BuildConfig.NAVER_MAP_CLIENT_ID)
            .addHeader("X-NCP-APIGW-API-KEY", BuildConfig.NAVER_MAP_CLIENT_SECRET)
            .build()

        Thread {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "reverseGeocode 실패: ${response.code}")
                        postAddressInfoResult(null, onResult)
                        return@use
                    }

                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        postAddressInfoResult(null, onResult)
                        return@use
                    }

                    val json = JSONObject(body)
                    val results = json.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        postAddressInfoResult(null, onResult)
                        return@use
                    }

                    val first = results.getJSONObject(0)

                    val region = first.optJSONObject("region")
                    val area1 = region?.optJSONObject("area1")?.optString("name", "")
                    val area2 = region?.optJSONObject("area2")?.optString("name", "")
                    val area3 = region?.optJSONObject("area3")?.optString("name", "")

                    val land = first.optJSONObject("land")
                    val name = land?.optString("name", "")
                    val number1 = land?.optString("number1", "")
                    val number2 = land?.optString("number2", "")

                    val address = listOf(area1, area2, area3, name, number1, number2)
                        .filter { !it.isNullOrBlank() }
                        .joinToString(" ")

                    val info = PotholeAddressInfo(
                        fullAddress = address,
                        area1 = area1,
                        area2 = area2,
                        area3 = area3
                    )
                    postAddressInfoResult(info, onResult)
                }
            } catch (e: Exception) {
                Log.e(TAG, "reverseGeocode 예외", e)
                postAddressInfoResult(null, onResult)
            }
        }.start()
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

    fun addPotholeFromCurrentLocationFromModel(
        photoBitmap: Bitmap?
    ): Boolean {
        // 0) locationManager 준비 여부 체크
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
        return potholeManager.addPotholeFromLocation(lat, lon, photoBitmap)
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