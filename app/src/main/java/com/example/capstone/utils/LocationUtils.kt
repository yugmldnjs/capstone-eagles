package com.example.capstone.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.net.toUri
import com.example.capstone.BuildConfig
import com.example.capstone.database.BikiDatabase
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.util.regex.Pattern
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.*

object LocationUtils {

    private const val TAG = "LocationUtils"

    suspend fun getAddressFromFile(context: Context, filePath: String): String {
        val (lat, lon) = if (filePath.contains("Events")) {
            getLocationFromDb(context,filePath)
        } else {
            getVideoLocation(context, filePath.toUri())
        }

        Log.d(TAG, "getAddressFromFile: $lat, $lon")
        // 콜백 기반 비동기 함수를 suspend로 감싸서 "기다리게" 만들기
        return suspendCoroutine { cont ->
            getAddressFromLocation(lat, lon) { address ->
                val result = address ?: "위치 정보 없음"
                Log.d(TAG, "getAddressFromFile callback: $result")

                // 여기서 getAddressFromFile의 리턴값을 확정짓고 코루틴 재개
                cont.resume(result)
            }
        }
    }

    fun getVideoLocation(context: Context, videoUri: Uri): Pair<Double, Double> {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, videoUri)
            // 영상 메타데이터 중 '위치' 키값을 가져옵니다. (형식 예: "+37.5665+126.9780/")
            val locationMetadata = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)

            if (locationMetadata != null) {
                // 이상한 기호(+/-)로 되어있는 문자열을 파싱해서 숫자(위도, 경도)로 바꿉니다.
                return parseLocationString(locationMetadata)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // 리소스 해제 (매우 중요)
            retriever.release()
        }
        return Pair(0.0, 0.0)
    }

    suspend fun getLocationFromDb(context: Context,eventVideoPath: String): Pair<Double, Double> {
        return try {
            // DB에서 이벤트 찾기
            val database = BikiDatabase.getDatabase(context)
            val eventDao = database.eventDao()

            val event = eventDao.getEventByExtractedVideoPath(eventVideoPath)
            if (event != null && event.latitude != null && event.longitude != null) {
                Log.d("StorageActivity", "위치 정보 로딩 성공: ${event.latitude}, ${event.longitude}")
                Pair(event.latitude, event.longitude)
            } else {
                Pair(0.0, 0.0)
            }
        } catch (e: Exception) {
            Log.e("StorageActivity", "위치 정보 로딩 실패", e)
            Pair(0.0, 0.0)
        }
    }

    // [핵심 함수 2] "+37.5665+126.9780/" 같은 문자열을 위도, 경도 숫자로 분리
    fun parseLocationString(location: String): Pair<Double, Double> {
        // ISO-6709 형식 파싱을 위한 정규식
        val pattern = Pattern.compile("([+-]\\d+\\.\\d+)([+-]\\d+\\.\\d+)")
        val matcher = pattern.matcher(location)

        if (matcher.find()) {
            val lat = matcher.group(1)?.toDoubleOrNull() ?: 0.0
            val lon = matcher.group(2)?.toDoubleOrNull() ?: 0.0
            return Pair(lat, lon)
        }
        return Pair(0.0, 0.0)
    }

    // [핵심 함수 3] 위도/경도 숫자를 한글 주소로 변환
//    fun getAddressFromLocation(context: Context,latitude: Double, longitude: Double): String {
//        if (latitude == 0.0 && longitude == 0.0) return "위치 정보 없음"
//        return try {
//            val geocoder = Geocoder(context, Locale.KOREA)
//            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
//            if (!addresses.isNullOrEmpty()) addresses[0].getAddressLine(0) else "알 수 없는 위치"
//        } catch (e: Exception) {
//            "위치 변환 실패"
//        }
//    }

    fun calculateDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val earthRadius = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)

        val a = sin(dLat / 2).pow(2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2)

        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadius * c
    }

    // ✅ 주행 방향(방위각) 계산: 0~360도, 0=북, 90=동
    fun calculateBearing(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)

        val y = sin(dLon) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) -
                sin(lat1Rad) * cos(lat2Rad) * cos(dLon)
        var brng = Math.toDegrees(atan2(y, x))
        brng = (brng + 360.0) % 360.0
        return brng
    }

    // 위치 업로드용 양자화 (지금 MapFragment에 있는 로직 그대로)
    fun quantizeLatLon(lat: Double, lon: Double, factor: Double = 1000.0): Pair<Double, Double> {
        val qLat = kotlin.math.round(lat * factor) / factor
        val qLon = kotlin.math.round(lon * factor) / factor
        return qLat to qLon
    }

    private val httpClient by lazy { OkHttpClient() }
    // 메인 스레드로 콜백을 보내기 위한 Handler
    private val mainHandler = Handler(Looper.getMainLooper())


    fun getAddressFromLocation(
        latitude: Double,
        longitude: Double,
        callback: (String?) -> Unit
    ) {
        Thread {
            Log.d(TAG, "----------------------------------------------------")
            // 네이버 Reverse Geocoding은 x=경도, y=위도
            val coords = "$longitude,$latitude"
            Log.d(TAG, "fetchAddressForPothole: $coords")

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

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e(TAG, "reverseGeocode 실패: code=${response.code}")
                        postResult(null, callback)
                        return@use
                    }

                    val body = response.body?.string()
                    if (body.isNullOrEmpty()) {
                        postResult(null, callback)
                        return@use
                    }

                    val address = parseAddressFromJson(body)
                    postResult(address, callback)
                }
            } catch (e: Exception) {
                Log.e(TAG, "reverseGeocode 예외", e)
                postResult(null, callback)
            }
        }.start()
    }

    private fun parseAddressFromJson(body: String): String? {
        val json = JSONObject(body)
        val results = json.optJSONArray("results") ?: return null
        if (results.length() == 0) return null

        // 일단 첫 번째 결과 사용 (roadaddr, addr 순서라면 roadaddr가 먼저 올 확률 높음)
        val first = results.getJSONObject(0)

        val region = first.optJSONObject("region")
        val area1 = region?.optJSONObject("area1")?.optString("name", "")
        val area2 = region?.optJSONObject("area2")?.optString("name", "")
        val area3 = region?.optJSONObject("area3")?.optString("name", "")

        val land = first.optJSONObject("land")
        val name = land?.optString("name", "")
        val number1 = land?.optString("number1", "")
        val number2 = land?.optString("number2", "")
        val numbers = listOf(number1, number2)
            .filter { !it.isNullOrBlank() }
            .joinToString("-")

        val address = listOf(area1, area2, area3, name, numbers)
            .filter { !it.isNullOrBlank() }
            .joinToString(" ")

        return address.ifBlank { null }
    }

    /**
     * 항상 메인 스레드에서 콜백을 호출하기 위한 헬퍼
     */
    private fun postResult(value: String?, callback: (String?) -> Unit) {
        mainHandler.post {
            callback(value)
        }
    }

}
