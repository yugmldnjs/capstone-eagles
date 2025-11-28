package com.example.capstone.ml

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import android.graphics.Bitmap
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import android.os.Parcel
import android.os.Parcelable
import kotlin.math.max
import kotlin.math.min

/**
 * 포트홀 감지 결과 하나를 표현하는 데이터 클래스
 */
data class PotholeDetection(
    val score: Float,
    val cx: Float,   // 0~1 정규화된 중심 x
    val cy: Float,   // 0~1 정규화된 중심 y
    val w: Float,    // 0~1 정규화된 폭
    val h: Float     // 0~1 정규화된 높이
) : Parcelable {

    constructor(parcel: Parcel) : this(
        score = parcel.readFloat(),
        cx = parcel.readFloat(),
        cy = parcel.readFloat(),
        w = parcel.readFloat(),
        h = parcel.readFloat()
    )

    override fun writeToParcel(parcel: Parcel, flags: Int) {
        parcel.writeFloat(score)
        parcel.writeFloat(cx)
        parcel.writeFloat(cy)
        parcel.writeFloat(w)
        parcel.writeFloat(h)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<PotholeDetection> {
        override fun createFromParcel(parcel: Parcel): PotholeDetection =
            PotholeDetection(parcel)

        override fun newArray(size: Int): Array<PotholeDetection?> =
            arrayOfNulls(size)
    }
}

/**
 * TFLite 포트홀 감지 모델 래퍼
 * - 지금 단계: 모델/라벨 안전하게 로드되는지 확인
 * - 다음 단계: ImageProxy → 입력 텐서 변환 + 출력 파싱 구현
 */
class PotholeDetector(
    private val context: Context
) {

    companion object {
        private const val TAG = "PotholeDetector"

        // assets 안의 파일명 (필요하면 이름 맞게 수정)
        private const val MODEL_FILE = "exp36_best_float16.tflite"
        private const val LABEL_FILE = "labels.txt"
    }

    private val interpreter: Interpreter
    private val labels: List<String>

    // ★ 모델 입력 사이즈 (로그에서 shape=[1, 320, 320, 3])
    private val inputWidth = 320
    private val inputHeight = 320
    private val inputChannels = 3

    // ★ 재사용할 입력 버퍼 (float32)
    private val imgData: ByteBuffer = ByteBuffer.allocateDirect(
        4 * inputWidth * inputHeight * inputChannels
    ).apply {
        order(ByteOrder.nativeOrder())
    }

    // 카메라 프레임을 RGB Bitmap으로 만들 때 재사용
    private var rgbBitmap: Bitmap? = null

    init {
        // 1) 모델 로드
        val modelBuffer = loadModelFile(context, MODEL_FILE)
        val options = Interpreter.Options().apply {
            // 필요 시 스레드 조정
            setNumThreads(4)
            // GPU delegate는 나중에 안정화되면 붙이는 걸 추천
        }
        interpreter = Interpreter(modelBuffer, options)
        Log.d(TAG, "TFLite interpreter created")

        // 2) 라벨 로드
        labels = loadLabels(context, LABEL_FILE)
        Log.d(TAG, "labels loaded: $labels")

        // 3) 디버깅용: 입력/출력 텐서 정보 찍기
        logModelInfo()
    }

    /**
     * assets 에서 .tflite 파일을 mmap 으로 로드
     */
    private fun loadModelFile(context: Context, fileName: String): ByteBuffer {
        val assetFileDescriptor = context.assets.openFd(fileName)
        FileInputStream(assetFileDescriptor.fileDescriptor).use { input ->
            val fileChannel: FileChannel = input.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
        }
    }

    /**
     * labels.txt 로드
     * - 한 줄 = 한 클래스 (지금은 "pothole" 한 줄만 있음)
     */
    private fun loadLabels(context: Context, fileName: String): List<String> {
        return context.assets.open(fileName).bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() }.map { it.trim() }.toList()
        }
    }

    /**
     * 모델 입출력 shape 찍어보기 (개발/디버깅용)
     */
    private fun logModelInfo() {
        try {
            val inputCount = interpreter.inputTensorCount
            val outputCount = interpreter.outputTensorCount
            Log.d(TAG, "Input tensor count  = $inputCount")
            Log.d(TAG, "Output tensor count = $outputCount")

            for (i in 0 until inputCount) {
                val tensor = interpreter.getInputTensor(i)
                Log.d(TAG, "Input[$i] name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
            }
            for (i in 0 until outputCount) {
                val tensor = interpreter.getOutputTensor(i)
                Log.d(TAG, "Output[$i] name=${tensor.name()}, shape=${tensor.shape().contentToString()}, type=${tensor.dataType()}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to log model info", e)
        }
    }

    /**
     * ImageProxy(YUV_420_888)를 바로 NV21 → ARGB 변환해서 Bitmap으로 만든다.
     * - 기존 JPEG 압축/디코딩 과정을 없애서 CPU 부하를 크게 줄인다.
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val width = image.width
        val height = image.height

        // 1) planes → NV21 바이트 배열 구성 (기존 코드와 동일한 순서)
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // NV21: Y + VU interleaved
        yBuffer.get(nv21, 0,      ySize)
        vBuffer.get(nv21, ySize,  vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        // 2) NV21 → ARGB_8888 Bitmap
        val bitmap = rgbBitmap?.takeIf { it.width == width && it.height == height }
            ?: Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                rgbBitmap = it
            }

        val frameSize = width * height
        val argb = IntArray(frameSize)

        var yp = 0
        for (j in 0 until height) {
            var uvIndex = frameSize + (j shr 1) * width
            var u = 0
            var v = 0

            for (i in 0 until width) {
                val y = 0xFF and nv21[yp].toInt()

                // 2픽셀마다 VU 갱신
                if ((i and 1) == 0) {
                    v = 0xFF and nv21[uvIndex].toInt()
                    u = 0xFF and nv21[uvIndex + 1].toInt()
                    uvIndex += 2
                }

                val yClamped = (y - 16).coerceAtLeast(0)
                val y1192 = 1192 * yClamped
                val uShifted = u - 128
                val vShifted = v - 128

                var r = y1192 + 1634 * vShifted
                var g = y1192 - 833 * vShifted - 400 * uShifted
                var b = y1192 + 2066 * uShifted

                // 0 ~ 262143 범위로 클램프
                if (r < 0) r = 0 else if (r > 262143) r = 262143
                if (g < 0) g = 0 else if (g > 262143) g = 262143
                if (b < 0) b = 0 else if (b > 262143) b = 262143

                argb[yp] =
                    (0xFF shl 24) or
                            ((r shl 6) and 0x00FF0000) or
                            ((g shr 2) and 0x0000FF00) or
                            ((b shr 10) and 0x000000FF)

                yp++
            }
        }

        bitmap.setPixels(argb, 0, width, 0, 0, width, height)
        return bitmap
    }

    /**
     * 회전 보정
     */
    private fun rotateBitmap(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }

    /**
     * Bitmap을 모델 입력(ByteBuffer)에 채우기
     * - RGB 순서, 0~1로 정규화 (학습 시 설정에 따라 -1~1 등으로 바꾸면 됨)
     */
    private fun convertBitmapToInputBuffer(bitmap: Bitmap) {
        val resized = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

        imgData.rewind()

        for (y in 0 until inputHeight) {
            for (x in 0 until inputWidth) {
                val pixel = resized.getPixel(x, y)
                val r = ((pixel shr 16) and 0xFF) / 255.0f
                val g = ((pixel shr 8) and 0xFF) / 255.0f
                val b = (pixel and 0xFF) / 255.0f

                imgData.putFloat(r)
                imgData.putFloat(g)
                imgData.putFloat(b)
            }
        }
    }


    /**
     * ImageProxy를 그대로 받아서 포트홀 감지 수행
     * - 결과 좌표는 0~1 정규화된 cx, cy, w, h 기준
     */
    fun detect(image: ImageProxy): List<PotholeDetection> {
        // 1) YUV -> Bitmap
        val bitmap = imageProxyToBitmap(image)

        // 2) 회전 보정
        val rotationDegrees = image.imageInfo.rotationDegrees
        val rotatedBitmap = rotateBitmap(bitmap, rotationDegrees)

        // 3) Bitmap -> 입력 버퍼
        convertBitmapToInputBuffer(rotatedBitmap)

        // 4) 출력 버퍼 준비 (shape: [1, 5, 2100])
        val output = Array(1) { Array(5) { FloatArray(2100) } }

        // 5) 추론 실행
        interpreter.run(imgData, output)

        // 6) 결과 파싱
        val rawDetections = mutableListOf<PotholeDetection>()

        val channels = output[0]              // size = 5
        if (channels.size < 5) {
            Log.e(TAG, "Unexpected output channel size: ${channels.size}")
            return emptyList()
        }

        val xs = channels[0]                  // cx
        val ys = channels[1]                  // cy
        val ws = channels[2]                  // w
        val hs = channels[3]                  // h
        val scores = channels[4]              // score

        val numBoxes = scores.size            // 2100

        val scoreThreshold = 0.4f             // 필요에 따라 조정

        for (i in 0 until numBoxes) {
            val score = scores[i]
            if (score < scoreThreshold) continue

            val cx = xs[i]
            val cy = ys[i]
            val w = ws[i]
            val h = hs[i]

            rawDetections.add(
                PotholeDetection(
                    score = score,
                    cx = cx,
                    cy = cy,
                    w = w,
                    h = h
                )
            )
        }

        // ★ NMS로 겹치는 박스 정리
        val finalDetections = applyNms(
            detections = rawDetections,
            iouThreshold = 0.5f,
            maxDetections = 5       // 필요하면 3~10 등으로 조절
        )

        Log.d(
            TAG,
            "detect() raw=${rawDetections.size} filtered=${finalDetections.size} (score >= $scoreThreshold)"
        )

        return finalDetections
    }

    /**
     * Non-Max Suppression (NMS)
     * - 점수 높은 박스부터 하나씩 고르고
     * - 이미 고른 박스들과 IoU가 threshold 이상 겹치는 애들은 버린다
     */
    private fun applyNms(
        detections: List<PotholeDetection>,
        iouThreshold: Float = 0.5f,
        maxDetections: Int = 5
    ): List<PotholeDetection> {
        if (detections.isEmpty()) return emptyList()

        // 점수 높은 순으로 정렬
        val sorted = detections.sortedByDescending { it.score }
        val result = mutableListOf<PotholeDetection>()

        fun iou(a: PotholeDetection, b: PotholeDetection): Float {
            // cx, cy, w, h 는 0~1 정규화라고 가정
            val ax1 = a.cx - a.w / 2f
            val ay1 = a.cy - a.h / 2f
            val ax2 = a.cx + a.w / 2f
            val ay2 = a.cy + a.h / 2f

            val bx1 = b.cx - b.w / 2f
            val by1 = b.cy - b.h / 2f
            val bx2 = b.cx + b.w / 2f
            val by2 = b.cy + b.h / 2f

            val interX1 = max(ax1, bx1)
            val interY1 = max(ay1, by1)
            val interX2 = min(ax2, bx2)
            val interY2 = min(ay2, by2)

            val interW = max(0f, interX2 - interX1)
            val interH = max(0f, interY2 - interY1)
            val interArea = interW * interH
            if (interArea <= 0f) return 0f

            val areaA = a.w * a.h
            val areaB = b.w * b.h
            return interArea / (areaA + areaB - interArea)
        }

        for (det in sorted) {
            if (result.size >= maxDetections) break

            var keep = true
            for (kept in result) {
                if (iou(det, kept) > iouThreshold) {
                    keep = false
                    break
                }
            }
            if (keep) {
                result.add(det)
            }
        }

        return result
    }

    fun close() {
        interpreter.close()
    }
}
