package com.example.capstone.worker

import android.content.Context
import android.util.Log
import androidx.preference.PreferenceManager
import androidx.work.*
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import com.example.capstone.database.BikiDatabase
import com.example.capstone.database.EventDao
import com.example.capstone.database.EventEntity
import com.example.capstone.util.SrtExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale


/**
 * 이벤트 영상 추출 Worker (SRT 파일 포함)
 *
 * 1. 이벤트 전후 10초 영상 추출 (FFmpeg)
 * 2. 해당 구간의 SRT도 추출 (SrtExtractor)
 */
class EventExtractionWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val database = BikiDatabase.getDatabase(applicationContext)
        val eventDao = database.eventDao()

        try {
            // pending 상태의 이벤트들 조회
            val pendingEvents = eventDao.getPendingExtractions()

            Log.d(TAG, "📋 추출 대기 중인 이벤트: ${pendingEvents.size}개")

            pendingEvents.forEach { event ->
                extractEventVideoAndSrt(event, eventDao)
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "❌ 이벤트 추출 실패", e)
            Result.retry()
        }
    }

    /**
     * 이벤트 영상 + SRT 추출
     */
    private suspend fun extractEventVideoAndSrt(event: EventEntity, eventDao: EventDao) {
        // videoFilePath 확인
        val videoPath = event.videoFilePath ?: run {
            Log.e(TAG, "❌ Event ${event.id}: videoFilePath가 null")
            return
        }

        val videoFile = File(videoPath)
        if (!videoFile.exists()) {
            Log.e(TAG, "❌ Event ${event.id}: 영상 파일 없음 - $videoPath")
            eventDao.update(event.copy(status = "failed"))
            return
        }

        // SRT 파일 경로 확인
        val srtFile = File(videoFile.parent, videoFile.nameWithoutExtension + ".srt")
        if (!srtFile.exists()) {
            Log.w(TAG, "⚠️ Event ${event.id}: SRT 파일 없음 - ${srtFile.path}")
            // SRT 없어도 영상은 추출
        }

        // 상태 업데이트: extracting
        eventDao.update(event.copy(status = "extracting"))
        Log.d(TAG, "🎬 Event ${event.id}: 추출 시작")
        Log.d(TAG, "   영상: ${videoFile.name}")
        if (srtFile.exists()) {
            Log.d(TAG, "   SRT: ${srtFile.name}")
        }

        try {
            // 1️⃣ 추출 구간 계산
            val videoStartTime = event.recordingStartTimestamp

            val eventTime = event.timestamp
            val eventRelativeTime = eventTime - videoStartTime

            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val setDuration = prefs.getString("event_video_duration", "60000") ?: "60000"
            val duration = setDuration.toLong()
            val startTime = maxOf(0, eventRelativeTime - duration/2)


            Log.d(TAG, "   이벤트 시각: ${eventRelativeTime}ms")
            Log.d(TAG, "   추출 구간: ${startTime}ms ~ ${startTime + duration}ms")

            // 2️⃣ 출력 파일 경로
            val outputDir = File(applicationContext.getExternalFilesDir(null), "Events")
            if (!outputDir.exists()) outputDir.mkdirs()

            val fileName = "events_${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA)
                .format(eventTime)}"
            val outputVideoFile = File(outputDir, "${fileName}.mp4")
            val outputSrtFile = File(outputDir, "${fileName}.srt")

            // 3️⃣ FFmpeg로 영상 추출
            val success = extractVideo(videoFile, outputVideoFile, startTime, duration, event.latitude, event.longitude)

            if (!success) {
                eventDao.update(event.copy(status = "failed"))
                Log.e(TAG, "❌ Event ${event.id}: FFmpeg 실패")
                return
            }


            // 4️⃣ SRT 추출 (원본 SRT가 있는 경우)
            if (srtFile.exists()) {
                val srtSuccess = SrtExtractor.extractSrtSegment(
                    sourceSrtFile = srtFile,
                    outputSrtFile = outputSrtFile,
                    extractStartMs = startTime,
                    extractDurationMs = duration
                )

                if (srtSuccess) {
                    Log.d(TAG, "✅ Event ${event.id}: SRT 추출 완료 - ${outputSrtFile.name}")

                    // 디버그: SRT 내용 출력
                    SrtExtractor.printSrtInfo(outputSrtFile)
                } else {
                    Log.w(TAG, "⚠️ Event ${event.id}: SRT 추출 실패")
                }
            }

            // 5️⃣ DB 업데이트
            eventDao.update(event.copy(
                extractedVideoPath = outputVideoFile.absolutePath,
                status = "completed"
            ))

            Log.d(TAG, "✅ Event ${event.id}: 추출 완료")
            Log.d(TAG, "   영상: ${outputVideoFile.name}")
            if (outputSrtFile.exists()) {
                Log.d(TAG, "   SRT: ${outputSrtFile.name} (${outputSrtFile.length()} bytes)")
            }

        } catch (e: Exception) {
            eventDao.update(event.copy(status = "failed"))
            Log.e(TAG, "❌ Event ${event.id}: 추출 중 오류", e)
        }
    }

    /**
     * FFmpeg를 사용한 영상 추출
     */
    private fun extractVideo(
        sourceFile: File,
        outputFile: File,
        startTimeMs: Long,
        durationMs: Long,
        latitude: Double?,
        longitude: Double?
    ): Boolean {
        try {
            val startSeconds = startTimeMs / 1000.0
            val durationSeconds = durationMs / 1000.0

            val gpsMetadata = if (latitude != null && longitude != null) {
                // FFmpeg는 location 태그에 ISO 6709 표준 형식(+lat+lon/)을 사용합니다.
                // 예: +35.1812-126.9105/
                String.format(Locale.KOREA, "-metadata location=%+.4f%+.4f/ ", latitude, longitude)
            } else {
                "" // 위치 정보가 없으면 빈 문자열
            }

            Log.d(TAG, "gps: $gpsMetadata")



            val command = "-i ${sourceFile.absolutePath} " +
                    "-ss $startSeconds " +
                    "-t $durationSeconds " +
                    "-c copy " +
                    gpsMetadata +
                    outputFile.absolutePath

            Log.d(TAG, "🎬 FFmpeg 명령: $command")

            val session = FFmpegKit.execute(command)

            return if (ReturnCode.isSuccess(session.returnCode)) {
                Log.d(TAG, "✅ FFmpeg 성공: ${outputFile.name}")
                Log.d(TAG, "   로그: ${session.output}")
                true
            } else {
                Log.e(TAG, "❌ FFmpeg 실패: ${session.returnCode}")
                Log.e(TAG, "   로그: ${session.output}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ FFmpeg 실행 오류", e)
            return false
        }
    }

    companion object {
        private const val TAG = "EventExtractionWorker"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"

        /**
         * 추출 작업 예약
         */
        fun scheduleExtraction(context: Context) {
            val workRequest = OneTimeWorkRequestBuilder<EventExtractionWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .build()
                )
                .build()

            WorkManager.getInstance(context).enqueue(workRequest)
            Log.d(TAG, "📋 이벤트 추출 작업 예약")
        }
    }
}