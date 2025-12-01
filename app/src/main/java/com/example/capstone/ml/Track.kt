package com.example.capstone.ml

data class Track(
    val id: Int,
    var bbox: FloatArray, // x, y, w, h
    var score: Float,
    var classId: Int,
    var lost: Int = 0,
    var frameId: Int,

    // 화면에 그릴 때 필요한 좌표
    var x1: Float,
    var y1: Float,
    var x2: Float,
    var y2: Float,
    var clsName: String
)