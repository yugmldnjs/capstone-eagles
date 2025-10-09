package com.example.capstone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData


// 이 메인뷰모델 파일은 가로 <-> 세로 모드로 변경할 때 기존의 상태들(카메라 녹화중, 플래쉬 켜짐 등)을 보존하기 위한 파일
class MainViewModel : ViewModel() {
    private val _isMapVisible = MutableLiveData<Boolean>(false)
    val isMapVisible: LiveData<Boolean> get() = _isMapVisible

    fun setMapVisible(isVisible: Boolean) {
        if (_isMapVisible.value != isVisible) {
            _isMapVisible.value = isVisible
        }
    }

    // --- 플래시 상태 ---
    private val _isFlashOn = MutableLiveData<Boolean>(false) // 초기값은 false
    val isFlashOn: LiveData<Boolean> get() = _isFlashOn

    // --- 녹화 상태 ---
    private val _isRecording = MutableLiveData<Boolean>(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

    // 서비스에서 호출하여 녹화 상태 동기화
    fun setRecordingState(isRecording: Boolean) {
        _isRecording.value = isRecording
    }

    fun toggleFlash() {
        _isFlashOn.value = !(_isFlashOn.value ?: false)
    }
}