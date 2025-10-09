/*
package com.example.capstone

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.capstone.databinding.FragmentCameraBinding
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var camera: Camera? = null
    private lateinit var cameraExecutor: ExecutorService

    private val activityResultLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            if (permissions.entries.all { it.value }) {
                binding.viewFinder.post { startCamera() }
            } else {
                Toast.makeText(requireContext(), "권한이 거부되었습니다.", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()

        if (allPermissionsGranted()) {
            binding.viewFinder.post { startCamera() }
        } else {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS)
        }
        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.recordVideoEvent.observe(viewLifecycleOwner) { recordVideo ->
            if (recordVideo == true) {
                toggleRecording()
                viewModel.doneRecordingVideo()
            }
        }
        viewModel.isFlashOn.observe(viewLifecycleOwner) { isOn ->
            camera?.cameraControl?.enableTorch(isOn)
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

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    viewLifecycleOwner, cameraSelector, preview, videoCapture
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecording() {
        if (recording != null) {
            recording?.stop()
            recording = null
            return
        }
        if (!allPermissionsGranted()) {
            Toast.makeText(requireContext(), "카메라 및 마이크 권한이 필요합니다.", Toast.LENGTH_SHORT).show()
            return
        }

        val name = "Blackbox-${SimpleDateFormat(FILENAME_FORMAT, Locale.KOREA).format(System.currentTimeMillis())}.mp4"
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/MyBlackboxVideos")
            }
        }

        val mediaStoreOutputOptions = MediaStoreOutputOptions
            .Builder(requireActivity().contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(contentValues)
            .build()

        recording = videoCapture?.output
            ?.prepareRecording(requireContext(), mediaStoreOutputOptions)
            ?.apply {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    withAudioEnabled()
                }
            }
            ?.start(ContextCompat.getMainExecutor(requireContext())) { recordEvent ->
                when (recordEvent) {
                    is VideoRecordEvent.Start -> Toast.makeText(requireContext(), "녹화 시작", Toast.LENGTH_SHORT).show()
                    is VideoRecordEvent.Finalize -> {
                        if (!recordEvent.hasError()) {
                            Toast.makeText(requireContext(), "영상 저장 완료: ${recordEvent.outputResults.outputUri}", Toast.LENGTH_SHORT).show()
                        } else {
                            recording?.close()
                            recording = null
                            Log.e(TAG, "영상 저장 실패: ${recordEvent.error}")
                        }
                    }
                }
            }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraFragment"
        private const val FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"
        private val REQUIRED_PERMISSIONS =
            mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ).apply {
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}
8/
 */