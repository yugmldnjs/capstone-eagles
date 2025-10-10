package com.example.capstone

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.google.firebase.auth.FirebaseAuth

class MainActivity : AppCompatActivity() {
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Firebase Auth 초기화
        auth = FirebaseAuth.getInstance()

        // 현재 로그인된 사용자 확인
        val currentUser = auth.currentUser

        val intent = if (currentUser != null) {
            // 이미 로그인되어 있으면 메인 화면으로
            Intent(this, MainActivity2::class.java)
        } else {
            // 로그인되어 있지 않으면 로그인 화면으로
            Intent(this, LogInActivity::class.java)
        }

        startActivity(intent)
        finish()
    }
}