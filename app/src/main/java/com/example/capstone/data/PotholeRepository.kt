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

    fun uploadPothole(lat: Double, lon: Double) {
        val data = hashMapOf(
            "latitude" to lat,
            "longitude" to lon,
            "createdAt" to Timestamp.now()
        )

        collection.add(data)
            .addOnSuccessListener { docRef ->
                Log.d("PotholeRepo", "Pothole created: ${docRef.id}")
            }
            .addOnFailureListener { e ->
                Log.e("PotholeRepo", "Failed to upload pothole", e)
            }
    }

    fun listenAllPotholes(
        onUpdate: (List<PotholeData>) -> Unit
    ): ListenerRegistration {
        return collection
            .orderBy("lastDetectedAt", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, e ->
                if (e != null) {
                    Log.e("PotholeRepo", "listenAllPotholes error", e)
                    return@addSnapshotListener
                }

                if (snapshot == null) {
                    onUpdate(emptyList())
                    return@addSnapshotListener
                }

                val list = snapshot.documents.mapNotNull { doc ->
                    val lat = doc.getDouble("latitude")
                    val lon = doc.getDouble("longitude")
                    val ts = doc.getTimestamp("lastDetectedAt")?.toDate()?.time ?: 0L

                    if (lat == null || lon == null) {
                        null
                    } else {
                        PotholeData(
                            id = doc.id,
                            latitude = lat,
                            longitude = lon,
                            createdAt = ts
                        )
                    }
                }

                Log.d("PotholeRepo", "all potholes: ${list.size}")
                onUpdate(list)
            }
    }
}
