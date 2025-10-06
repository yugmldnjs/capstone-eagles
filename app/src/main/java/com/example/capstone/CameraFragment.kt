/*
package com.example.capstone

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
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

    private var imageCapture: ImageCapture? = null
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
        // ViewModel의 takePhotoEvent observe 우선 미리보기를 위해 사진 촬영으로 넣어놓음.
        viewModel.takePhotoEvent.observe(viewLifecycleOwner) { takePhoto ->
            if (takePhoto == true) { // 사진 촬영 요청이 오면
                takePhoto() // 사진 촬영 실행
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

            imageCapture = ImageCapture.Builder().build()
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
                        viewLifecycleOwner, cameraSelector, preview, miniPreview, imageCapture
                    )
                } else {
                    // 보이지 않을 때는 miniPreview 없이 메인 프리뷰
                    camera = cameraProvider.bindToLifecycle(
                        viewLifecycleOwner, cameraSelector, preview, imageCapture
                    )
                }
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val imageCapture = imageCapture ?: return

        // 사진을 저장할 파일 생성
        val photoFile = File(
            outputDirectory,
            SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA).format(System.currentTimeMillis()) + ".jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            cameraExecutor, // 사진 저장은 백그라운드 스레드
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    val savedUri = Uri.fromFile(photoFile)
                    // val msg = "사진 캡처 성공: $savedUri"
                    // UI 업데이트는 메인 스레드에서
                    activity?.runOnUiThread {

                    }
                }
            })
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
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}*/
