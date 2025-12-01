package com.example.capstone.ml

data class BoundingBox(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val cls: Int = 0,
    val cnf: Float,
    val clsName: String = "pothole"
)