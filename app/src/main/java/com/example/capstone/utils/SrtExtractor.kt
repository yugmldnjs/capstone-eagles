package com.example.capstone.util

import android.util.Log
import java.io.File

/**
 * SRT 파일 파싱 및 추출 유틸리티
 * 
 * 원본 영상의 SRT에서 특정 시간 구간만 추출하여
 * 이벤트 영상용 SRT 생성
 */
object SrtExtractor {
    private const val TAG = "SrtExtractor"
    
    /**
     * SRT 엔트리 데이터 클래스
     */
    data class SrtEntry(
        val sequence: Int,
        val startTime: Long,  // milliseconds
        val endTime: Long,    // milliseconds
        val content: String
    )
    
    /**
     * 원본 SRT에서 특정 시간 구간을 추출하여 새 SRT 파일 생성
     * 
     * @param sourceSrtFile 원본 SRT 파일
     * @param outputSrtFile 출력 SRT 파일
     * @param extractStartMs 추출 시작 시간 (원본 영상 기준, ms)
     * @param extractDurationMs 추출 구간 길이 (ms)
     * @return 성공 여부
     */
    fun extractSrtSegment(
        sourceSrtFile: File,
        outputSrtFile: File,
        extractStartMs: Long,
        extractDurationMs: Long
    ): Boolean {
        try {
            if (!sourceSrtFile.exists()) {
                Log.e(TAG, "❌ 원본 SRT 파일 없음: ${sourceSrtFile.path}")
                return false
            }
            
            // 1. 원본 SRT 파싱
            val allEntries = parseSrtFile(sourceSrtFile)
            Log.d(TAG, "📄 원본 SRT 엔트리 수: ${allEntries.size}")
            
            // 2. 추출 구간 계산
            val extractEndMs = extractStartMs + extractDurationMs
            
            // 3. 해당 구간의 엔트리만 필터링
            val filteredEntries = allEntries.filter { entry ->
                // 엔트리가 추출 구간과 겹치는지 확인
                entry.endTime >= extractStartMs && entry.startTime <= extractEndMs
            }
            
            Log.d(TAG, "✂️ 추출된 엔트리 수: ${filteredEntries.size}")
            
            if (filteredEntries.isEmpty()) {
                Log.w(TAG, "⚠️ 추출 구간에 SRT 엔트리 없음")
                // 빈 SRT 파일 생성
                outputSrtFile.writeText("")
                return true
            }
            
            // 4. 타임스탬프 조정 (추출 시작 시간을 0으로)
            val adjustedEntries = filteredEntries.mapIndexed { index, entry ->
                SrtEntry(
                    sequence = index + 1,  // 1부터 다시 시작
                    startTime = maxOf(0, entry.startTime - extractStartMs),
                    endTime = minOf(extractDurationMs, entry.endTime - extractStartMs),
                    content = entry.content
                )
            }
            
            // 5. SRT 파일 생성
            val srtContent = buildSrtContent(adjustedEntries)
            outputSrtFile.writeText(srtContent)
            
            Log.d(TAG, "✅ SRT 추출 완료: ${outputSrtFile.name}")
            return true
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ SRT 추출 실패", e)
            return false
        }
    }
    
    /**
     * SRT 파일 파싱
     */
    private fun parseSrtFile(srtFile: File): List<SrtEntry> {
        val entries = mutableListOf<SrtEntry>()
        val lines = srtFile.readLines()
        
        var i = 0
        while (i < lines.size) {
            val line = lines[i].trim()
            
            // 시퀀스 번호 찾기
            if (line.toIntOrNull() != null) {
                val sequence = line.toInt()
                
                // 타임스탬프 라인 (다음 줄)
                if (i + 1 < lines.size) {
                    val timeLine = lines[i + 1].trim()
                    val (startTime, endTime) = parseTimestamp(timeLine)
                    
                    // 내용 라인들 (타임스탬프 다음부터 빈 줄까지)
                    val contentLines = mutableListOf<String>()
                    var j = i + 2
                    while (j < lines.size && lines[j].trim().isNotEmpty()) {
                        contentLines.add(lines[j])
                        j++
                    }
                    
                    entries.add(
                        SrtEntry(
                            sequence = sequence,
                            startTime = startTime,
                            endTime = endTime,
                            content = contentLines.joinToString("\n")
                        )
                    )
                    
                    i = j  // 다음 엔트리로 이동
                } else {
                    i++
                }
            } else {
                i++
            }
        }
        
        return entries
    }
    
    /**
     * 타임스탬프 파싱
     * "00:02:30,000 --> 00:02:31,000" → (150000, 151000)
     */
    private fun parseTimestamp(timeLine: String): Pair<Long, Long> {
        val parts = timeLine.split("-->").map { it.trim() }
        if (parts.size != 2) {
            return Pair(0L, 0L)
        }
        
        val startMs = timeToMillis(parts[0])
        val endMs = timeToMillis(parts[1])
        
        return Pair(startMs, endMs)
    }
    
    /**
     * 시간 문자열을 밀리초로 변환
     * "00:02:30,500" → 150500
     */
    private fun timeToMillis(timeStr: String): Long {
        try {
            // "00:02:30,500" 형식
            val parts = timeStr.split(":")
            if (parts.size != 3) return 0L
            
            val hours = parts[0].toLong()
            val minutes = parts[1].toLong()
            val secondsParts = parts[2].split(",")
            val seconds = secondsParts[0].toLong()
            val millis = if (secondsParts.size > 1) secondsParts[1].toLong() else 0L
            
            return hours * 3600000 + minutes * 60000 + seconds * 1000 + millis
        } catch (e: Exception) {
            Log.e(TAG, "타임스탬프 파싱 오류: $timeStr", e)
            return 0L
        }
    }
    
    /**
     * 밀리초를 SRT 시간 형식으로 변환
     * 150500 → "00:02:30,500"
     */
    private fun millisToTime(millis: Long): String {
        val hours = millis / 3600000
        val minutes = (millis % 3600000) / 60000
        val seconds = (millis % 60000) / 1000
        val ms = millis % 1000
        
        return String.format("%02d:%02d:%02d,%03d", hours, minutes, seconds, ms)
    }
    
    /**
     * SRT 엔트리 리스트를 SRT 파일 내용으로 변환
     */
    private fun buildSrtContent(entries: List<SrtEntry>): String {
        val builder = StringBuilder()
        
        entries.forEach { entry ->
            builder.append("${entry.sequence}\n")
            builder.append("${millisToTime(entry.startTime)} --> ${millisToTime(entry.endTime)}\n")
            builder.append("${entry.content}\n")
            builder.append("\n")
        }
        
        return builder.toString()
    }
    
    /**
     * 테스트/디버그용: SRT 파일 내용 출력
     */
    fun printSrtInfo(srtFile: File) {
        if (!srtFile.exists()) {
            Log.w(TAG, "SRT 파일 없음: ${srtFile.path}")
            return
        }
        
        val entries = parseSrtFile(srtFile)
        Log.d(TAG, "=== SRT 파일 정보: ${srtFile.name} ===")
        Log.d(TAG, "총 엔트리 수: ${entries.size}")
        
        entries.take(3).forEach { entry ->
            Log.d(TAG, "[${entry.sequence}] ${millisToTime(entry.startTime)} --> ${millisToTime(entry.endTime)}")
            Log.d(TAG, "  ${entry.content.replace("\n", " | ")}")
        }
        
        if (entries.size > 3) {
            Log.d(TAG, "... (${entries.size - 3}개 더)")
        }
    }
}
