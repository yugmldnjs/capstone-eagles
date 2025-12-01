package com.example.capstone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.View
import com.example.capstone.ml.Track

class PotholeOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val TAG = "PotholeOverlayView"
    }

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

    private var tracks: List<Track> = emptyList()

    fun updateTracks(newTracks: List<Track>) {
        tracks = newTracks
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (tracks.isEmpty()) return

        val w = width.toFloat()
        val h = height.toFloat()

        for (t in tracks) {
            // bbox = [cx, cy, w, h] (0~1 정규화) 라고 가정
            val cx = t.bbox[0].coerceIn(0f, 1f)
            val cy = t.bbox[1].coerceIn(0f, 1f)
            val bw = t.bbox[2].coerceIn(0.01f, 1f)
            val bh = t.bbox[3].coerceIn(0.01f, 1f)

            val left = (cx - bw / 2f) * w
            val top = (cy - bh / 2f) * h
            val right = (cx + bw / 2f) * w
            val bottom = (cy + bh / 2f) * h

            // 박스
            canvas.drawRect(RectF(left, top, right, bottom), boxPaint)

            // 텍스트: ID + score
            val label = "ID=${t.id} (%.2f)".format(t.score)
            canvas.drawText(label, left, top - 8f, textPaint)
        }
    }
}
