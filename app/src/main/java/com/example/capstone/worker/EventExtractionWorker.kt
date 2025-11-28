package com.example.capstone.worker

import android.content.Context
import android.util.Log
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.example.capstone.database.BikiDatabase
import com.example.capstone.database.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale


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
        val videoPath = inputData.getString("video_path") ?: return Result.failure()
        val videoFile = File(videoPath)

        if (!videoFile.exists()) {
            Log.e("ExtractionWorker", "원본 영상이 없음: $videoPath")
            return Result.failure()
        }

        // 해당 영상과 연관된 pending 이벤트들 가져오기
        val pendingEvents = eventDao.getPendingExtractions()
            .filter { it.videoFilePath == videoPath }

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

    /**
     * FFmpeg로 특정 구간 추출
     * @param sourceVideo 원본 녹화 파일 (예: ride_1234567890.mp4)
     * @param event 추출할 이벤트 정보
     * @return 성공 여부
     */
    private suspend fun extractEventVideo(sourceVideo: File, event: EventEntity): Boolean {
        // 1. 영상 시작 시간과 이벤트 시간 계산
        val videoCreationTime = sourceVideo.lastModified()
        val eventTime = event.timestamp
        val eventOffsetSeconds = (event.timestamp - videoCreationTime) / 1000.0

        // 2. 추출 구간 계산 (이벤트 30초 전 ~ 30초 후)
        val startTime = maxOf(0.0, eventOffsetSeconds - 3.0)
        val duration = 6.0  // 60초

        // 3. 출력 파일 경로 생성
        val outputFile = File(
            applicationContext.getExternalFilesDir("Events"),  // 이벤트 전용 폴더
            "events_${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
                .format(eventTime)}.mp4"
        ).apply { parentFile?.mkdirs() }

        // 4. FFmpeg 명령어 실행
        val command = "-ss $startTime -i ${sourceVideo.absolutePath} -t $duration -c copy ${outputFile.absolutePath}"

        return withContext(Dispatchers.IO) {
            try {
                // 상태 업데이트: extracting
                eventDao.update(event.copy(status = "extracting"))

                // FFmpeg 실행 (동기)
                val session = FFmpegKit.execute(command)

                if (session.returnCode.isValueSuccess) {
                    // 추출 성공 - DB 업데이트
                    eventDao.update(event.copy(
                        extractedVideoPath = outputFile.absolutePath,
                        status = "completed"
                    ))

                    // 메타데이터 JSON 저장
                    //saveEventMetadata(event, outputFile)

                    Log.d("ExtractionWorker", "✅ 추출 완료: ${eventTime}")
                    true
                } else {
                    Log.e("ExtractionWorker", "❌ 추출 실패: ${session.output}")
                    eventDao.update(event.copy(status = "failed"))
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "추출 중 예외 발생", e)
                eventDao.update(event.copy(status = "failed"))
                false
            }
        }
    }
    companion object {
        private const val TAG = "ExtractionWorker"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
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