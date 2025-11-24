package com.example.capstone.ml

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder

/**
 * 포트홀 감지 결과 하나를 표현하는 데이터 클래스
 * (나중에 박스 좌표, 스코어 등 채울 예정)
 */
data class PotholeDetection(
    val score: Float,
    val cx: Float,   // 0~1 정규화된 중심 x
    val cy: Float,   // 0~1 정규화된 중심 y
    val w: Float,    // 0~1 정규화된 폭
    val h: Float     // 0~1 정규화된 높이
)


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
     * ImageProxy(YUV_420_888)를 Bitmap으로 변환
     * - 성능은 아주 빠르진 않지만, 구현이 간단해서 초기 테스트용으로 적당합니다.
     */
    private fun imageProxyToBitmap(image: ImageProxy): Bitmap {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)

        // U, V 가 섞이는 순서에 주의
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = YuvImage(
            nv21,
            ImageFormat.NV21,
            image.width,
            image.height,
            null
        )

        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 100, out)
        val jpegBytes = out.toByteArray()

        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
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
        val detections = mutableListOf<PotholeDetection>()

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

            // 여기서는 일단 0~1 정규화 값이라고 가정
            detections.add(
                PotholeDetection(
                    score = score,
                    cx = cx,
                    cy = cy,
                    w = w,
                    h = h
                )
            )
        }

        Log.d(TAG, "detect() found ${detections.size} potholes (score >= $scoreThreshold)")

        return detections
    }

    fun close() {
        interpreter.close()
    }
}
