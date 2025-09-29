package com.example.capstone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {

        installSplashScreen()
        super.onCreate(savedInstanceState)

        // 메인 화면으로 이동
        val intent = Intent(this, MainActivity2::class.java)

        startActivity(intent)

        finish()
    }
}