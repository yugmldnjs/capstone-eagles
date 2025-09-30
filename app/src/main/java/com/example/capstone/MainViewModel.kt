package com.example.capstone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData


// 이 메인뷰모델 파일은 가로 <-> 세로 모드로 변경할 때 기존의 상태들(카메라 녹화중, 플래쉬 켜짐 등)을 보존하기 위한 파일
class MainViewModel : ViewModel() {
    // isRecording 상태를 Activity 대신 ViewModel에서! 그래서 회전 시 잘 됨.
    var isRecording = false
    // var isFlashOn = false
    private val _isMapVisible = MutableLiveData<Boolean>(false)
    val isMapVisible: LiveData<Boolean> get() = _isMapVisible

    fun setMapVisible(isVisible: Boolean) {
        if (_isMapVisible.value != isVisible) {
            _isMapVisible.value = isVisible
        }
    }



    // cameraX
    private val _takePhotoEvent = MutableLiveData<Boolean>()
    val takePhotoEvent: LiveData<Boolean> get() = _takePhotoEvent

    fun requestTakePhoto() {
        _takePhotoEvent.value = true
    }

    fun doneTakingPhoto() {
        _takePhotoEvent.value = false
    }


    // --- 플래시 상태 ---
    private val _isFlashOn = MutableLiveData<Boolean>(false) // 초기값은 false
    val isFlashOn: LiveData<Boolean> get() = _isFlashOn

    fun toggleFlash() {
        _isFlashOn.value = !(_isFlashOn.value ?: false)
    }



}