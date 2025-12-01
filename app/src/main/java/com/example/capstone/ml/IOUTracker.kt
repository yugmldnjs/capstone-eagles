package com.example.capstone.ml

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class IOUTracker(
    private val maxLost: Int = 5,
    private val iouThreshold: Float = 0.2f,          // IoU 기준 살짝 완화
    private val minDetectionConfidence: Float = 0.3f,
    private val maxDetectionConfidence: Float = 0.9f // 지금은 안 씀, 자리만 유지
) {
    private val tracks = mutableMapOf<Int, Track>()
    private var nextTrackId = 0
    private var frameCount = 0

    // 빠르게 지나가도 같은 물체로 인정할 최대 중심 거리 (0~1 정규화 좌표 기준)
    private val centerDistThreshold = 0.6f

    // box: [cx, cy, w, h]
    private fun iouXywh(box1: FloatArray, box2: FloatArray): Float {
        val x1A = box1[0] - box1[2] / 2f
        val y1A = box1[1] - box1[3] / 2f
        val x2A = box1[0] + box1[2] / 2f
        val y2A = box1[1] + box1[3] / 2f

        val x1B = box2[0] - box2[2] / 2f
        val y1B = box2[1] - box2[3] / 2f
        val x2B = box2[0] + box2[2] / 2f
        val y2B = box2[1] + box2[3] / 2f

        val xA = max(x1A, x1B)
        val yA = max(y1A, y1B)
        val xB = min(x2A, x2B)
        val yB = min(y2A, y2B)

        val interArea = max(0f, xB - xA) * max(0f, yB - yA)
        val boxAArea = box1[2] * box1[3]
        val boxBArea = box2[2] * box2[3]
        val unionArea = boxAArea + boxBArea - interArea

        return if (unionArea > 0f) interArea / unionArea else 0f
    }

    fun update(bboxes: List<BoundingBox>): List<Track> {
        frameCount++

        // 감지가 하나도 없으면 lost++ 후 삭제
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

        // BoundingBox -> (bbox, (classId, score, clsName))
        val detections = bboxes.map {
            floatArrayOf(it.cx, it.cy, it.w, it.h) to Triple(it.cls, it.cnf, it.clsName)
        }.toMutableList()

        val trackIds = tracks.keys.toList()

        // 1. 기존 트랙과 현재 감지 결과 매칭
        for (trackId in trackIds) {
            if (detections.isEmpty()) break

            val currentTrackBbox = tracks[trackId]!!.bbox

            var bestMatchIndex = -1
            var bestMatchScore = -1f
            var bestMatchDetection: Pair<FloatArray, Triple<Int, Float, String>>? = null

            detections.forEachIndexed { index, (bbox, info) ->
                val iou = iouXywh(currentTrackBbox, bbox)

                val dx = currentTrackBbox[0] - bbox[0]
                val dy = currentTrackBbox[1] - bbox[1]
                val centerDist = sqrt(dx * dx + dy * dy)

                val isCloseCenter = centerDist <= centerDistThreshold

                // 💡 매칭 점수: IoU가 어느 정도면 IoU 사용,
                //   아니면 center가 충분히 가까우면 0.3,
                //   둘 다 아니면 0
                val score = when {
                    iou >= iouThreshold -> iou
                    isCloseCenter       -> 0.3f
                    else                -> 0f
                }

                if (score > bestMatchScore) {
                    bestMatchScore = score
                    bestMatchIndex = index
                    bestMatchDetection = detections[index]
                }
            }

            // 점수가 0보다 크면 매칭 성공으로 판단
            if (bestMatchScore > 0f && bestMatchDetection != null) {
                val (newBbox, newInfo) = bestMatchDetection!!
                val (newClassId, newScore, newClsName) = newInfo

                _updateTrack(
                    trackId = trackId,
                    frameId = frameCount,
                    newBbox = newBbox,
                    newScore = newScore,
                    classId = newClassId,
                    newClsName = newClsName
                )
                detections.removeAt(bestMatchIndex)
            } else {
                // 매칭 실패 → lost++
                tracks[trackId]!!.lost++
                if (tracks[trackId]!!.lost > maxLost) {
                    tracks.remove(trackId)
                }
            }
        }

        // 2. 남은 감지는 새 트랙 생성
        for ((bbox, info) in detections) {
            val (classId, score, clsName) = info
            _addTrack(frameCount, bbox, score, classId, clsName)
        }

        return tracks.values.toList()
    }

    private fun _addTrack(
        frameId: Int,
        bbox: FloatArray,
        score: Float,
        classId: Int,
        clsName: String
    ) {
        if (score >= minDetectionConfidence) {
            val x1 = bbox[0] - (bbox[2] / 2f)
            val y1 = bbox[1] - (bbox[3] / 2f)
            val x2 = bbox[0] + (bbox[2] / 2f)
            val y2 = bbox[1] + (bbox[3] / 2f)

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
                clsName = clsName
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
        val track = tracks[trackId] ?: return

        val x1 = newBbox[0] - (newBbox[2] / 2f)
        val y1 = newBbox[1] - (newBbox[3] / 2f)
        val x2 = newBbox[0] + (newBbox[2] / 2f)
        val y2 = newBbox[1] + (newBbox[3] / 2f)

        track.bbox = newBbox
        track.score = newScore
        track.classId = classId
        track.lost = 0
        track.frameId = frameId
        track.x1 = x1
        track.y1 = y1
        track.x2 = x2
        track.y2 = y2
        track.clsName = newClsName
    }

    fun reset() {
        tracks.clear()
        nextTrackId = 0
        frameCount = 0
    }
}