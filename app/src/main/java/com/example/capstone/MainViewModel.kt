package com.example.capstone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData


// 이 메인뷰모델 파일은 가로 <-> 세로 모드로 변경할 때 기존의 상태들(카메라 녹화중, 플래쉬 켜짐 등)을 보존하기 위한 파일
class MainViewModel : ViewModel() {
    // isRecording 상태를 Activity 대신 ViewModel에서! 그래서 회전 시 잘 됨.
    // var isRecording = false
    // var isFlashOn = false
    private val _isMapVisible = MutableLiveData<Boolean>(false)
    val isMapVisible: LiveData<Boolean> get() = _isMapVisible

    fun setMapVisible(isVisible: Boolean) {
        if (_isMapVisible.value != isVisible) {
            _isMapVisible.value = isVisible
        }
    }

    // --- 녹화 상태 ---
    // 역할 1: 현재 녹화 상태를 저장하고 UI가 관찰하도록 함 (예: 버튼 아이콘 변경용)
    private val _isRecording = MutableLiveData<Boolean>(false)
    val isRecording: LiveData<Boolean> get() = _isRecording

    // 서비스로부터 녹화 상태를 전달받아 LiveData를 업데이트하는 함수
    fun setIsRecording(isRec: Boolean) {
        if (_isRecording.value != isRec) {
            _isRecording.value = isRec
        }
    }
    // requestToggleRecording, recordVideoEvent 등은 서비스 방식으로 바뀌었으므로 삭제
    // 플래시 코드도 이동
}