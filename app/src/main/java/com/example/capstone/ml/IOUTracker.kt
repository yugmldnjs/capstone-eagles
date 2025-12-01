package com.example.capstone.ml

import kotlin.math.max
import kotlin.math.min

class IOUTracker(
    private val maxLost: Int = 2,
    private val iouThreshold: Float = 0.5f,
    private val minDetectionConfidence: Float = 0.4f,
    private val maxDetectionConfidence: Float = 0.7f
    // trackerOutputFormat은 Android 환경에서는 불필요할 수 있습니다.
) {
    private val tracks = mutableMapOf<Int, Track>()
    private var nextTrackId = 0
    private var frameCount = 0

    // 파이썬의 iou_xywh와 유사한 기능을 하는 함수 (x, y, w, h 형식 가정)
    private fun iouXywh(box1: FloatArray, box2: FloatArray): Float {
        // box: [cx, cy, w, h] 형식이라고 가정
        val x1A = box1[0] - box1[2] / 2
        val y1A = box1[1] - box1[3] / 2
        val x2A = box1[0] + box1[2] / 2
        val y2A = box1[1] + box1[3] / 2

        val x1B = box2[0] - box2[2] / 2
        val y1B = box2[1] - box2[3] / 2
        val x2B = box2[0] + box2[2] / 2
        val y2B = box2[1] + box2[3] / 2

        val xA = max(x1A, x1B)
        val yA = max(y1A, y1B)
        val xB = min(x2A, x2B)
        val yB = min(y2A, y2B)

        // 교집합 영역
        val interArea = max(0f, xB - xA) * max(0f, yB - yA)

        // 합집합 영역
        val boxAArea = box1[2] * box1[3]
        val boxBArea = box2[2] * box2[3]
        val unionArea = boxAArea + boxBArea - interArea

        return if (unionArea > 0) interArea / unionArea else 0f
    }

    // 객체 감지 결과를 기반으로 추적 상태를 업데이트하는 핵심 함수
    fun update(
        bboxes: List<BoundingBox>
    ): List<Track> {
        frameCount++

        // ✅ 감지 결과가 아예 없을 때: 모든 트랙 lost++ 후, maxLost 초과시 제거
        if (bboxes.isEmpty()) {
            val ids = tracks.keys.toList()
            for (id in ids) {
                val t = tracks[id] ?: continue
                t.lost++
                if (t.lost > maxLost) {
                    tracks.remove(id)
                }
            }
            return tracks.values.toList()
        }

        // BoundingBox 리스트를 IoU Tracker가 처리하기 쉬운 (bbox, classId, score) 튜플 형태로 변환
        val detections = bboxes.map {
            floatArrayOf(it.cx, it.cy, it.w, it.h) to Triple(it.cls, it.cnf, it.clsName)
        }.toMutableList()

        val updatedTracks = mutableListOf<Int>()

        // 1. 기존 트랙과 현재 감지된 객체 매칭
        val trackIds = tracks.keys.toList()
        for (trackId in trackIds) {
            if (detections.isEmpty()) break

            val currentTrackBbox = tracks[trackId]!!.bbox

            // 현재 트랙과 IoU가 가장 높은 감지 객체를 찾습니다.
            var bestMatchIoU = -1f
            var bestMatchIndex = -1
            var bestMatchDetection: Pair<FloatArray, Triple<Int, Float, String>>? = null

            detections.forEachIndexed { index, (bbox, _) ->
                val iou = iouXywh(currentTrackBbox, bbox)

                // 센터 거리 계산 (0~1 정규화 좌표 기준)
                val dx = currentTrackBbox[0] - bbox[0]
                val dy = currentTrackBbox[1] - bbox[1]
                val centerDist = kotlin.math.sqrt(dx * dx + dy * dy)

                // “같은 물체 같다”라고 볼 최소 기준
                val isCloseCenter = centerDist < 0.35f   // 필요하면 조절

                // 점수 정의: IoU 가 크거나, 센터가 충분히 가까우면 높게
                val score = if (iou > 0f) iou else if (isCloseCenter) 0.3f else 0f

                if (score > bestMatchIoU) {
                    bestMatchIoU = score
                    bestMatchIndex = index
                    bestMatchDetection = detections[index]
                }
            }

            // IoU 임계값을 넘으면 매칭 성공
            if (bestMatchIoU >= iouThreshold && bestMatchDetection != null) {
                val (newBbox, newInfo) = bestMatchDetection!!
                val (newClassId, newScore, newClsName) = newInfo

                _updateTrack(
                    trackId,
                    frameCount,
                    newBbox,
                    newScore,
                    newClassId,
                    newClsName
                )
                updatedTracks.add(trackId)
                detections.removeAt(bestMatchIndex) // 매칭된 감지 객체는 제거
            } else {
                // 매칭 실패 시 손실 카운트 증가 및 제거 확인
                tracks[trackId]!!.lost++
                if (tracks[trackId]!!.lost > maxLost) {
                    tracks.remove(trackId)
                }
            }
        }

        // 2. 매칭되지 않은(남은) 감지 객체는 새로운 트랙으로 추가
        for ((bbox, info) in detections) {
            val (classId, score, clsName) = info
            _addTrack(frameCount, bbox, score, classId, clsName)
        }

        // 3. 현재 존재하는 트랙 목록 반환
        return tracks.values.toList()
    }

    private fun _addTrack(frameId: Int, bbox: FloatArray, score: Float, classId: Int, clsName: String) {
        if (score >= minDetectionConfidence) { // 최소 신뢰도 검사
            // 화면에 그릴 때 필요한 x1, y1, x2, y2 계산
            val x1 = bbox[0] - (bbox[2] / 2F)
            val y1 = bbox[1] - (bbox[3] / 2F)
            val x2 = bbox[0] + (bbox[2] / 2F)
            val y2 = bbox[1] + (bbox[3] / 2F)

            val track = Track(
                id = nextTrackId,
                bbox = bbox,
                score = score,
                classId = classId,
                frameId = frameId,
                x1 = x1,
                y1 = y1,
                x2 = x2,
                y2 = y2,
                clsName = clsName // 값 전달
            )
            tracks[nextTrackId] = track
            nextTrackId++
        }
    }

    private fun _updateTrack(
        trackId: Int,
        frameId: Int,
        newBbox: FloatArray,
        newScore: Float,
        classId: Int,
        newClsName: String
    ) {
        val track = tracks[trackId]!!

        // 화면에 그릴 때 필요한 x1, y1, x2, y2 재계산
        track.x1 = newBbox[0] - (newBbox[2] / 2F)
        track.y1 = newBbox[1] - (newBbox[3] / 2F)
        track.x2 = newBbox[0] + (newBbox[2] / 2F)
        track.y2 = newBbox[1] + (newBbox[3] / 2F)

        track.bbox = newBbox
        track.score = newScore
        track.classId = classId
        track.lost = 0
        track.frameId = frameId

        // 새로 추가된 필드 업데이트
        // track.x1 = x1
        // track.y1 = y1
        // track.x2 = x2
        // track.y2 = y2
        track.clsName = newClsName // 값 업데이트
    }

    // 다른 필요한 유틸리티 함수 (예: 트랙 목록 반환 등)
    // fun getTracks(): List<Track> = tracks.values.toList()

    fun reset() {
        tracks.clear()
        nextTrackId = 0
        frameCount = 0
    }
}