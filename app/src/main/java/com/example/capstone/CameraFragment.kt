package com.example.capstone

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.example.capstone.databinding.FragmentCameraBinding
import androidx.fragment.app.activityViewModels


class CameraFragment : Fragment() {
    private val viewModel: MainViewModel by activityViewModels()
    // ViewBinding을 위한 변수
    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private var recordingService: RecordingService? = null
    private var isServiceBound = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as RecordingService.LocalBinder
            recordingService = binder.getService()
            isServiceBound = true
            // 서비스가 연결되면, 이 프래그먼트의 viewFinder를 사용해 미리보기를 보여달라고 요청합니다.
            recordingService?.attachPreview(binding.viewFinder.surfaceProvider)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            recordingService = null
        }
    }

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
            }
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
        // 권한이 없으면 요청
        if (!allPermissionsGranted()) {
            activityResultLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    override fun onStart() {
        super.onStart()
        // 프래그먼트가 화면에 보이기 시작할 때 서비스에 연결(bind)합니다.
        Intent(requireActivity(), RecordingService::class.java).also { intent ->
            requireActivity().bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        super.onStop()
        // 프래그먼트가 화면에서 사라질 때 서비스 연결을 해제(unbind)합니다.
        if (isServiceBound) {
            recordingService?.detachPreview() // 미리보기 해제 요청
            requireActivity().unbindService(connection)
            isServiceBound = false
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {

        // ▼▼▼▼▼ [수정] 필수 권한에 오디오 녹음 권한 추가 ▼▼▼▼▼
        private val REQUIRED_PERMISSIONS =
            mutableListOf (
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO // 오디오 권한 추가
            ).apply {
                // 안드로이드 구 버전을 위해 저장소 쓰기 권한을 추가
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
    }
}