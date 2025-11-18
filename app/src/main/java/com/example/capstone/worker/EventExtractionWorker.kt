package com.example.capstone.worker

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.capstone.database.BikiDatabase
import com.example.capstone.database.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import androidx.core.net.toUri


class EventExtractionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val eventDao = BikiDatabase.getDatabase(context).eventDao()

    /**
     * WorkManager가 백그라운드에서 자동 실행
     * @return Result.success() 또는 Result.retry()
     */
    override suspend fun doWork(): Result {
        // 입력 데이터에서 영상 경로 가져오기
        val videoUriString = inputData.getString("video_uri") ?: return Result.failure()
        val videoUri = videoUriString.toUri()

        // MediaStore URI를 실제 파일 경로로 변환
        val videoPath = getPathFromUri(videoUri) ?: return Result.failure()
        val videoFile = File(videoPath)

        if (!videoFile.exists()) {
            Log.e("ExtractionWorker", "원본 영상이 없음: $videoPath")
            return Result.failure()
        }

        // 해당 영상과 연관된 pending 이벤트들 가져오기
        val pendingEvents = eventDao.getPendingExtractions()
            .filter { it.videoUri == videoUriString }

        if (pendingEvents.isEmpty()) {
            Log.d("ExtractionWorker", "추출할 이벤트 없음")
            return Result.success()
        }

        Log.d("ExtractionWorker", "📹 ${pendingEvents.size}개 이벤트 추출 시작")

        var successCount = 0
        pendingEvents.forEach { event ->
            if (extractEventVideo(videoFile, event)) {
                successCount++
            }
        }

        return if (successCount > 0) Result.success() else Result.retry()
    }

    // ✅ MediaStore URI를 파일 경로로 변환
    private fun getPathFromUri(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        applicationContext.contentResolver.query(
            uri,
            projection,
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val columnIndex = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                return cursor.getString(columnIndex)
            }
        }
        return null
    }

    /**
     * FFmpeg로 특정 구간 추출
     * @param sourceVideo 원본 녹화 파일 (예: ride_1234567890.mp4)
     * @param event 추출할 이벤트 정보
     * @return 성공 여부
     */
    private suspend fun extractEventVideo(sourceVideo: File, event: EventEntity): Boolean {
        // 1. 영상 시작 시간
        val videoStartTime = event.recordingStartTimestamp

        // 이벤트 발생 시점
        val eventTime = event.timestamp

        // 이벤트가 영상 시작 후 몇 초에 발생했는지 계산
        val eventOffsetSeconds = (eventTime - videoStartTime) / 1000.0

        // 2. 추출 구간 계산 (이벤트 30초 전 ~ 30초 후)
        val startTime = maxOf(0.0, eventOffsetSeconds - 30.0)
        val duration = 60.0  // 60초

        // ✅ 3. 앱 폴더에 임시 파일 생성
        val tempDir = applicationContext.getExternalFilesDir("temp_events")
        if (tempDir == null) {
            Log.e(TAG, "❌ 임시 폴더 생성 실패!")
            return false
        }
        tempDir.mkdirs()
        val tempFile = File(tempDir, "Impact_${eventTime}_temp.mp4")

        // 이미 존재하면 삭제
        if (tempFile.exists()) {
            tempFile.delete()
            Log.d(TAG, "기존 임시 파일 삭제")
        }

        // 4. FFmpeg 명령어 실행
        val command = "-ss $startTime -i ${sourceVideo.absolutePath} -t $duration -c copy ${tempFile.absolutePath}"

        return withContext(Dispatchers.IO) {
            try {
                // 상태 업데이트: extracting
                eventDao.update(event.copy(status = "extracting"))

                // FFmpeg 실행 (동기)
                val session = FFmpegKit.execute(command)

                // 추출 실패
                if (!session.returnCode.isValueSuccess) {
                    // 추출 실패 - DB 업데이트
                    eventDao.update(event.copy(status = "failed"))
                    tempFile.delete()
                    return@withContext false
                }

                // ✅ FFmpeg 성공
                // ✅ 5. MediaStore에 등록
                val finalUri = copyToMediaStore(tempFile, eventTime)

                if (finalUri == null) {
                    eventDao.update(event.copy(status = "failed"))
                    tempFile.delete()
                    return@withContext false
                }

                // ✅ 6. 최종 경로 가져오기
                val finalPath = getPathFromUri(finalUri)

                // DB 업데이트
                eventDao.update(event.copy(
                    extractedVideoPath = finalPath ?: finalUri.toString(),
                    status = "completed"
                ))

                // 임시 파일 삭제
                tempFile.delete()
                Log.d(TAG, "11. 임시 파일 삭제 완료")

                // 메타데이터 JSON 저장
                // saveEventMetadata(event, outputFile)

                Log.d(TAG, "✅ 추출 완료: ${eventTime}")
                true

            } catch (e: Exception) {
                Log.e(TAG, "추출 중 예외 발생", e)
                eventDao.update(event.copy(status = "failed"))
                tempFile.delete()
                false
            }
        }
    }
    // ✅ 임시 파일을 MediaStore로 복사
    private fun copyToMediaStore(tempFile: File, timestamp: Long): Uri? {
        try {
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "Impact_${timestamp}.mp4")
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackboxVideos/Events")
                }
            }

            val uri = applicationContext.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                contentValues
            ) ?: return null

            // 파일 복사
            applicationContext.contentResolver.openOutputStream(uri)?.use { outputStream ->
                tempFile.inputStream().use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            }

            return uri

        } catch (e: Exception) {
            Log.e(TAG, "❌ copyToMediaStore 실패", e)
            return null
        }
    }

    companion object {
        private const val TAG = "ExtractionWorker"
    }
    /**
     * 이벤트 메타데이터를 JSON으로 저장
     * - 영상과 같은 폴더에 _meta.json 파일 생성
     */
//    private fun saveEventMetadata(event: EventEntity, videoFile: File) {
//        val metadata = JSONObject().apply {
//            put("timestamp", event.timestamp)
//            put("recordingStartTimestamp", event.recordingStartTimestamp)
//            put("type", event.type)
//            put("videoPath", videoFile.absolutePath)
//            put("latitude", event.latitude)
//            put("longitude", event.longitude)
//            put("speed", event.speed)
//            put("accelerometer", JSONObject().apply {
//                put("x", event.accelerometerX)
//                put("y", event.accelerometerY)
//                put("z", event.accelerometerZ)
//            })
//            event.gyroX?.let {
//                put("gyroscope", JSONObject().apply {
//                    put("x", event.gyroX)
//                    put("y", event.gyroY)
//                    put("z", event.gyroZ)
//                })
//            }
//        }
//
//        val metaFile = File(videoFile.parent, "${videoFile.nameWithoutExtension}_meta.json")
//        metaFile.writeText(metadata.toString())
//    }

}