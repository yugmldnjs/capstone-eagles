package com.example.capstone.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

class LocationRepository {

    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("locations")

    /** ✅ 사용자별로 1개의 문서만 유지 (add → set 변경) */
    fun uploadLocation(userId: String, lat: Double, lon: Double) {
        val data = mapOf(
            "userId" to userId,
            "latitude" to lat,
            "longitude" to lon,
            "timestamp" to Timestamp.now()
        )

        // 🔹 문서 ID를 userId로 고정해서 덮어쓰기
        collection.document(userId)
            .set(data)
            .addOnSuccessListener {
                Log.d("LocationRepo", "위치 갱신 성공: $userId ($lat,$lon)")
            }
            .addOnFailureListener { e ->
                Log.e("LocationRepo", "위치 갱신 실패", e)
            }
    }

    /**
     * 최근 N분 이내 사용자 위치만 실시간 수신
     * @param minutesAgo 몇 분 전까지의 데이터를 가져올지 (기본 2분)
     * @param onUpdate 위치 데이터 리스트 콜백
     */
    fun listenRecentLocations(
        minutesAgo: Int = 2,
        onUpdate: (List<LocationData>) -> Unit
    ): ListenerRegistration {
        return collection.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e("LocationRepo", "위치 수신 실패", e)
                return@addSnapshotListener
            }

            val now = System.currentTimeMillis()
            val thresholdMillis = minutesAgo * 60 * 1000L

            val recentLocations = snapshot?.documents
                ?.mapNotNull { doc ->
                    try {
                        val userId = doc.getString("userId") ?: return@mapNotNull null
                        val lat = doc.getDouble("latitude") ?: return@mapNotNull null
                        val lon = doc.getDouble("longitude") ?: return@mapNotNull null
                        val timestamp = doc.getTimestamp("timestamp") ?: return@mapNotNull null

                        // 타임스탬프 체크: 최근 N분 이내인가?
                        val age = now - timestamp.toDate().time
                        if (age > thresholdMillis) {
                            return@mapNotNull null
                        }

                        LocationData(userId, lat, lon, timestamp.toDate().time)
                    } catch (ex: Exception) {
                        Log.e("LocationRepo", "위치 파싱 실패", ex)
                        null
                    }
                }
                ?: emptyList()

            Log.d("LocationRepo", "최근 ${minutesAgo}분 이내 위치: ${recentLocations.size}개")
            onUpdate(recentLocations)
        }
    }
}

/**
 * 위치 데이터 클래스
 */
data class LocationData(
    val userId: String,
    val latitude: Double,
    val longitude: Double,
    val timestamp: Long
)