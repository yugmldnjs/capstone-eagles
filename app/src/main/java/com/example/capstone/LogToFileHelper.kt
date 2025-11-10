package com.example.capstone

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object LogToFileHelper {

    private var fileWriter: FileWriter? = null
    private const val TAG = "LogToFileHelper"

    fun startLogging(context: Context, fileNamePrefix: String) {
        if (fileWriter != null) {
            stopLogging() // 이미 열려있으면 닫고 새로 시작
        }

        try {
            // 파일 이름에 현재 시간을 포함하여 중복 방지
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date())
            val fileName = "$fileNamePrefix-$timestamp.txt"

            // 앱의 외부 저장소 캐시 디렉토리에 파일 생성
            // 이 경로는 사용자가 쉽게 접근할 수 있습니다.
            val logFile = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

            fileWriter = FileWriter(logFile, true) // true: 이어쓰기 모드
            Log.i(TAG, "로그 파일 생성 완료: ${logFile.absolutePath}")
            writeLog("--- 로그 기록 시작 ---")

        } catch (e: IOException) {
            Log.e(TAG, "로그 파일 생성 실패", e)
            fileWriter = null
        }
    }

    fun stopLogging() {
        try {
            writeLog("--- 로그 기록 종료 ---")
            fileWriter?.close()
            Log.i(TAG, "로그 파일 닫기 완료.")
        } catch (e: IOException) {
            Log.e(TAG, "로그 파일 닫기 실패", e)
        } finally {
            fileWriter = null
        }
    }

    fun writeLog(logMessage: String) {
        // 파일이 열려있을 때만 로그를 쓴다.
        fileWriter?.let {
            try {
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA).format(Date())
                it.append("[$timestamp] $logMessage\n")
                it.flush() // 버퍼에 있는 내용을 즉시 파일에 쓴다.
            } catch (e: IOException) {
                // 파일 쓰기 중 에러 발생 시 로그캣에만 출력
                Log.e(TAG, "로그 파일 쓰기 실패: $logMessage", e)
            }
        }
    }
}
