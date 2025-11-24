package com.example.capstone.ml

import android.content.Context
import android.util.Log
import androidx.camera.core.ImageProxy
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * 포트홀 감지 결과 하나를 표현하는 데이터 클래스
 * (나중에 박스 좌표, 스코어 등 채울 예정)
 */
data class PotholeDetection(
    val score: Float,
    // 나중에 박스 좌표, 화면 좌표 등 추가 예정
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
     * 실제 감지 함수 (다음 단계에서 구현)
     * - 지금은 아직 ImageProxy → 텐서 변환 / 출력 파싱 전이라 빈 리스트 반환
     */
    fun detect(image: ImageProxy): List<PotholeDetection> {
        // TODO: 1) YUV_420_888 → RGB 변환
        // TODO: 2) 640x640 등 모델 입력 크기로 리사이즈
        // TODO: 3) ByteBuffer에 채워서 interpreter.run() 호출
        // TODO: 4) 결과 파싱 + NMS
        return emptyList()
    }

    fun close() {
        interpreter.close()
    }
}
