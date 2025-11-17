package com.example.capstone.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.FieldValue

class LocationRepository {

    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("locations")

    /**
     * ✅ 사용자별로 1개의 문서만 유지
     * ✅ 개선 1: 서버 타임스탬프 사용 (클라이언트 시간 불일치 방지)
     * ✅ 개선 2: race condition 방지
     */
    fun uploadLocation(userId: String, lat: Double, lon: Double) {
        val data = hashMapOf(
            "userId" to userId,
            "latitude" to lat,
            "longitude" to lon,
            "timestamp" to FieldValue.serverTimestamp() // ✅ 서버 타임스탬프 사용
        )

        // 문서 ID를 userId로 고정해서 덮어쓰기
        collection.document(userId)
            .set(data)
            .addOnSuccessListener {
                Log.d("LocationRepo", "✅ 위치 갱신 성공: $userId ($lat,$lon)")
            }
            .addOnFailureListener { e ->
                Log.e("LocationRepo", "❌ 위치 갱신 실패: ${e.message}", e)
            }
    }

    /**
     * ✅ 최근 N분 이내 사용자 위치만 실시간 수신 (개선됨)
     * ✅ 개선 1: Firestore 쿼리에서 직접 필터링 (클라이언트 부하 감소)
     * ✅ 개선 2: 에러 처리 개선
     *
     * @param minutesAgo 몇 분 전까지의 데이터를 가져올지 (기본 2분)
     * @param onUpdate 위치 데이터 리스트 콜백
     */
    fun listenRecentLocations(
        minutesAgo: Int = 2,
        onUpdate: (List<LocationData>) -> Unit
    ): ListenerRegistration {
        val thresholdTime = Timestamp(
            java.util.Date(System.currentTimeMillis() - (minutesAgo * 60 * 1000L))
        )

        // ✅ Firestore 쿼리에서 직접 필터링 (성능 개선)
        return collection
            .whereGreaterThan("timestamp", thresholdTime)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("LocationRepo", "❌ 위치 수신 실패: ${e.message}", e)
                    onUpdate(emptyList()) // ✅ 에러 시 빈 리스트 반환
                    return@addSnapshotListener
                }

                val recentLocations = snapshot?.documents
                    ?.mapNotNull { doc ->
                        try {
                            val userId = doc.getString("userId") ?: return@mapNotNull null
                            val lat = doc.getDouble("latitude") ?: return@mapNotNull null
                            val lon = doc.getDouble("longitude") ?: return@mapNotNull null
                            val timestamp = doc.getTimestamp("timestamp") ?: return@mapNotNull null

                            LocationData(userId, lat, lon, timestamp.toDate().time)
                        } catch (ex: Exception) {
                            Log.e("LocationRepo", "❌ 위치 파싱 실패: ${ex.message}", ex)
                            null
                        }
                    }
                    ?: emptyList()

                Log.d("LocationRepo", "✅ 최근 ${minutesAgo}분 이내 위치: ${recentLocations.size}개")
                onUpdate(recentLocations)
            }
    }

    /**
     * ✅ 신규 추가: 오래된 위치 데이터 정리
     * - 주기적으로 호출하여 DB 용량 관리
     * - Firebase 요금 절약
     *
     * @param olderThanMinutes N분 이전 데이터 삭제 (기본 5분)
     */
    fun cleanOldLocations(olderThanMinutes: Int = 5) {
        val thresholdTime = Timestamp(
            java.util.Date(System.currentTimeMillis() - (olderThanMinutes * 60 * 1000L))
        )

        collection
            .whereLessThan("timestamp", thresholdTime)
            .get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) {
                    Log.d("LocationRepo", "✅ 삭제할 오래된 위치 데이터 없음")
                    return@addOnSuccessListener
                }

                val batch = db.batch()
                var count = 0

                snapshot.documents.forEach { doc ->
                    batch.delete(doc.reference)
                    count++
                }

                batch.commit()
                    .addOnSuccessListener {
                        Log.d("LocationRepo", "✅ 오래된 위치 ${count}개 삭제 완료")
                    }
                    .addOnFailureListener { e ->
                        Log.e("LocationRepo", "❌ 오래된 위치 삭제 실패: ${e.message}", e)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("LocationRepo", "❌ 오래된 위치 조회 실패: ${e.message}", e)
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