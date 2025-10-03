package com.example.capstone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.capstone.databinding.FragmentCameraBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import androidx.fragment.app.activityViewModels


class CameraFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    // ViewBinding을 위한 변수
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null  // 플래시 제어하려면 Camera 객체에 접근해야함.
    private lateinit var outputDirectory: File
    private lateinit var cameraExecutor: ExecutorService

    // 권한 요청
    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            // 권한이 모두 허용되었는지 확인
            var allPermissionsGranted = true
            permissions.entries.forEach {
                if (it.key in REQUIRED_PERMISSIONS && !it.value) {
                    allPermissionsGranted = false
                }
            }
            if (!allPermissionsGranted) {
                Toast.makeText(requireContext(), "권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            } else {
                binding.viewFinder.post {
                    startCamera() // 모든 권한이 허용되면 카메라 시작
            }}
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 사진이 저장될 디렉토리 설정 + 백그라운드 스레드 초기화
        outputDirectory = getOutputDirectory()
        cameraExecutor = Executors.newSingleThreadExecutor()


        if (allPermissionsGranted()) {
            binding.viewFinder.post {
                startCamera()
            }
        } else {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS)
        }
        // ViewModel의 녹화 이벤트를 관찰(observe)
        viewModel.recordVideoEvent.observe(viewLifecycleOwner) { recordVideo ->
            if (recordVideo == true) { // 녹화/중단 요청이 오면
                toggleRecording() // 녹화 토글 함수 실행
                viewModel.doneTakingPhoto() // 이벤트 처리 완료 알림
            }
        }

        viewModel.isFlashOn.observe(viewLifecycleOwner) { isOn ->
            // camera 객체가 null이 아닐 때만 실행
            camera?.cameraControl?.enableTorch(isOn)
        }

        viewModel.isMapVisible.observe(viewLifecycleOwner) { isVisible ->
            // 카메라가 이미 시작된 후에 상태가 변경된 경우,
            // mini_camera의 visibility가 변경되었을 수 있으므로 카메라를 다시 시작하여 바인딩을 업데이트.
            if (camera != null) {
                startCamera()
            }
        }


    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                .build()
            videoCapture = VideoCapture.withOutput(recorder)
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            // mini_camera 뷰를 먼저 찾아서 상태 확인
            val miniCameraView = requireActivity().findViewById<androidx.camera.view.PreviewView>(R.id.mini_camera)

            try {
                cameraProvider.unbindAll()

                // mini_camera가 VISIBLE일 때와 아닐 때를 구분 (사용자 설정 따라서)
                if (miniCameraView.visibility == View.VISIBLE) {
                    // 보일 때만 miniPreview를 생성하고 함께 바인딩
                    val miniPreview = Preview.Builder().build()
                    miniPreview.setSurfaceProvider(miniCameraView.surfaceProvider)
                    camera = cameraProvider.bindToLifecycle(
                        viewLifecycleOwner, cameraSelector, preview, miniPreview, videoCapture
                    )
                } else {
                    // 보이지 않을 때는 miniPreview 없이 메인 프리뷰
                    camera = cameraProvider.bindToLifecycle(
                        viewLifecycleOwner, cameraSelector, preview, videoCapture
                    )
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        val videoCapture = this.videoCapture ?: return

        // 현재 녹화 중인 경우 중단
        if (recording != null) {
            recording?.stop()
            recording = null
            return
        }

        // 녹화 시작
        val name = "Blackbox-${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackbox")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireActivity().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        val audioPermission = ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)

        recording = videoCapture.output
            .prepareRecording(requireContext(), mediaStoreOutputOptions)
            .apply {
                if (audioPermission == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            .start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> {
                        Toast.makeText(requireContext(), "녹화 시작", Toast.LENGTH_SHORT).show()
                    }
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            val msg = "영상 저장 완료: ${recordEvent.outputResults.outputUri}"
                            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun getOutputDirectory(): File {
        val mediaDir = requireActivity().externalMediaDirs.firstOrNull()?.let {
            File(it, resources.getString(R.string.app_name)).apply { mkdirs() }
        }
        return if (mediaDir != null && mediaDir.exists())
            mediaDir else requireActivity().filesDir
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO // 오디오 권한 추가
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}