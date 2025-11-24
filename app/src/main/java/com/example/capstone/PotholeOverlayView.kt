package com.example.capstone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.example.capstone.ml.PotholeDetection

class PotholeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val boxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f
        isAntiAlias = true
        color = 0xFFFF9800.toInt() // 주황색
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
        color = 0xFFFF9800.toInt()
        textSize = 36f
    }

    @Volatile
    private var detections: List<PotholeDetection> = emptyList()

    fun updateDetections(newDetections: List<PotholeDetection>) {
        detections = newDetections
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (detections.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (det in detections) {
            // PotholeDetection: cx, cy, w, h 가 0~1 범위라고 가정
            val cx = det.cx.coerceIn(0f, 1f)
            val cy = det.cy.coerceIn(0f, 1f)
            val bw = det.w.coerceAtLeast(0.01f).coerceAtMost(1f)
            val bh = det.h.coerceAtLeast(0.01f).coerceAtMost(1f)

            val left = (cx - bw / 2f) * w
            val top = (cy - bh / 2f) * h
            val right = (cx + bw / 2f) * w
            val bottom = (cy + bh / 2f) * h

            val rect = RectF(left, top, right, bottom)
            canvas.drawRect(rect, boxPaint)

            // 점수 텍스트
            val scoreText = String.format("%.2f", det.score)
            canvas.drawText(scoreText, left, top - 8f, textPaint)
        }
    }
}
