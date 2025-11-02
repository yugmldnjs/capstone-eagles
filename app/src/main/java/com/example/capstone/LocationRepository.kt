package com.example.capstone.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore

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

    /** 모든 사용자 위치 실시간 수신 */
    fun listenAllLocations(onUpdate: (List<Map<String, Any>>) -> Unit) =
        collection.addSnapshotListener { snapshot, e ->
            if (e != null) {
                Log.e("LocationRepo", "위치 수신 실패", e)
                return@addSnapshotListener
            }
            val list = snapshot?.documents?.mapNotNull { it.data } ?: emptyList()
            onUpdate(list)
        }
}
