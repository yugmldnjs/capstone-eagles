package com.example.capstone.util

import android.content.Context
import android.location.Geocoder
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.example.capstone.database.BikiDatabase
import java.util.Locale
import java.util.regex.Pattern

object LocationUtils {

    private const val TAG = "LocationUtils"

    suspend fun getAddressFromFile(context: Context, filePath: String): String {
        val (lat, lon) = if (filePath.contains("Events")) {
            getLocationFromDb(context,filePath)
        } else {
            getVideoLocation(context, filePath.toUri())
        }
        return getAddressFromLocation(context, lat, lon)
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
    fun getAddressFromLocation(context: Context,latitude: Double, longitude: Double): String {
        if (latitude == 0.0 && longitude == 0.0) return "위치 정보 없음"
        return try {
            val geocoder = Geocoder(context, Locale.KOREA)
            val addresses = geocoder.getFromLocation(latitude, longitude, 1)
            if (!addresses.isNullOrEmpty()) addresses[0].getAddressLine(0) else "알 수 없는 위치"
        } catch (e: Exception) {
            "위치 변환 실패"
        }
    }
}
