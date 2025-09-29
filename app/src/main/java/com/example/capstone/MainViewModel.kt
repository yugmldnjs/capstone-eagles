package com.example.capstone

import androidx.lifecycle.ViewModel

// 이 메인뷰모델 파일은 가로 <-> 세로 모드로 변경할 때 기존의 상태들(카메라 녹화중, 플래쉬 켜짐 등)을 보존하기 위한 파일
class MainViewModel : ViewModel() {
    // isRecording 상태를 Activity 대신 ViewModel에서
    var isRecording = false
    var isFlashOn = false
    var isMapVisible = false
}