package com.example.capstone.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.SetOptions
import java.util.Date
import kotlin.math.round

class PotholeRepository {

    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("potholes")

    /**
     * ✅ 포트홀 1건 업로드
     * - 같은 셀(양자화 좌표)은 한 문서에 누적
     * - totalCount 필드를 FieldValue.increment(1)로 증가
     */
    fun uploadPothole(lat: Double, lon: Double) {
        val (qLat, qLon) = quantizePotholeLatLon(lat, lon)
        val docId = "${qLat}_${qLon}"

        val data = hashMapOf(
            "latitude" to qLat,
            "longitude" to qLon,
            "totalCount" to FieldValue.increment(1),
            "lastDetectedAt" to Timestamp.now()
        )

        collection.document(docId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("PotholeRepo", "Pothole updated: $docId")
            }
            .addOnFailureListener { e ->
                Log.e("PotholeRepo", "Failed to upload pothole", e)
            }
    }

    /**
     * ✅ 최근 N시간 이내 포트홀만 실시간으로 듣기
     * - 나중에 맵에서 Firestore 기반으로 마커를 그리고 싶을 때 사용
     */
    fun listenRecentPotholes(
        hoursAgo: Long = 24,
        onUpdate: (List<PotholeData>) -> Unit
    ): ListenerRegistration {
        val now = System.currentTimeMillis()
        val cutoff = Timestamp(Date(now - hoursAgo * 60 * 60 * 1000))

        return collection
            .whereGreaterThanOrEqualTo("lastDetectedAt", cutoff)
            .orderBy("lastDetectedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("PotholeRepo", "listenRecentPotholes error", e)
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                val list = snapshot.documents.mapNotNull { doc ->
                    val lat = doc.getDouble("latitude")
                    val lon = doc.getDouble("longitude")
                    val count = doc.getLong("totalCount")?.toInt() ?: 1
                    val ts = doc.getTimestamp("lastDetectedAt")?.toDate()?.time ?: 0L

                    if (lat == null || lon == null) {
                        null
                    } else {
                        PotholeData(
                            id = doc.id,
                            latitude = lat,
                            longitude = lon,
                            createdAt = ts,
                            count = count
                        )
                    }
                }

                Log.d("PotholeRepo", "recent potholes: ${list.size}")
                onUpdate(list)
            }
    }

    /**
     * ✅ 포트홀용 위치 양자화
     * - factor가 클수록 셀이 더 촘촘해짐
     * - 3000.0 정도면 수~십 m 단위
     */
    private fun quantizePotholeLatLon(lat: Double, lon: Double): Pair<Double, Double> {
        val factor = 3000.0
        val qLat = round(lat * factor) / factor
        val qLon = round(lon * factor) / factor
        return qLat to qLon
    }
}
