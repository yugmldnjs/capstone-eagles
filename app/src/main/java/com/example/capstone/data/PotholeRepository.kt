package com.example.capstone.data

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Query
import android.graphics.Bitmap
import com.google.firebase.storage.FirebaseStorage
import java.io.ByteArrayOutputStream

class PotholeRepository {

    private val db = FirebaseFirestore.getInstance()
    private val collection = db.collection("potholes")
    private val storage = FirebaseStorage.getInstance().reference.child("pothole_photos")

    fun uploadPothole(
        lat: Double,
        lon: Double,
        photoBitmap: Bitmap? = null,
        onComplete: ((Boolean) -> Unit)? = null
    ) {
        Log.d("PotholeRepo", "uploadPothole called: hasPhoto=${photoBitmap != null}, lat=$lat, lon=$lon")
        val createdAt = Timestamp.now()

        val data = hashMapOf(
            "latitude" to lat,
            "longitude" to lon,
            "createdAt" to createdAt
        )

        // 1) 먼저 Firestore 문서 생성
        collection.add(data)
            .addOnSuccessListener { docRef ->
                Log.d("PotholeRepo", "Pothole created: ${docRef.id}")

                // 사진이 없으면 여기까지
                if (photoBitmap == null) {
                    onComplete?.invoke(true)
                    return@addOnSuccessListener
                }

                // 2) Bitmap -> JPEG 바이트로 압축
                val baos = ByteArrayOutputStream()
                val ok = photoBitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                if (!ok) {
                    Log.e("PotholeRepo", "Failed to compress pothole bitmap")
                    onComplete?.invoke(false)
                    return@addOnSuccessListener
                }
                val bytes = baos.toByteArray()

                // 3) Storage에 업로드 (문서 ID 기준으로 파일명 사용)
                val photoRef = storage.child("${docRef.id}.jpg")
                photoRef.putBytes(bytes)
                    .continueWithTask { task ->
                        if (!task.isSuccessful) {
                            throw task.exception ?: Exception("Photo upload failed")
                        }
                        photoRef.downloadUrl
                    }
                    .addOnSuccessListener { uri ->
                        // 4) Firestore 문서에 imageUrl 필드 저장
                        docRef.update("imageUrl", uri.toString())
                            .addOnSuccessListener {
                                Log.d("PotholeRepo", "imageUrl updated")
                                onComplete?.invoke(true)
                            }
                            .addOnFailureListener { e ->
                                Log.e("PotholeRepo", "Failed to update imageUrl", e)
                                onComplete?.invoke(false)
                            }
                    }
                    .addOnFailureListener { e ->
                        Log.e("PotholeRepo", "Failed to upload pothole photo", e)
                        onComplete?.invoke(false)
                    }
            }
            .addOnFailureListener { e ->
                Log.e("PotholeRepo", "Failed to upload pothole", e)
                onComplete?.invoke(false)
            }
    }

    fun listenAllPotholes(
        onUpdate: (List<PotholeData>) -> Unit
    ): ListenerRegistration {
        return collection
            .orderBy("createdAt", Query.Direction.DESCENDING)
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
                    val lat = doc.getDouble("latitude") ?: return@mapNotNull null
                    val lon = doc.getDouble("longitude") ?: return@mapNotNull null

                    val createdAt = doc.getTimestamp("createdAt")
                        ?.toDate()?.time
                        ?: 0L

                    val imageUrl = doc.getString("imageUrl")

                    PotholeData(
                        id = doc.id,
                        latitude = lat,
                        longitude = lon,
                        createdAt = createdAt,
                        imageUrl = imageUrl
                    )
                }

                Log.d("PotholeRepo", "all potholes: ${list.size}")
                onUpdate(list)
            }
    }
}
