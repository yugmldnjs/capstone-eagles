package com.example.capstone

import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.capstone.databinding.ActivityPasswordSearchBinding
import com.google.firebase.auth.FirebaseAuth

class PasswordSearch : AppCompatActivity() {

    private lateinit var binding: ActivityPasswordSearchBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // ✅ ViewBinding 먼저 설정
        binding = ActivityPasswordSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 상태바/내비게이션바 여백 처리
        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // ✅ FirebaseAuth 초기화
        auth = FirebaseAuth.getInstance()

        // 뒤로가기 버튼
        binding.PWSerachBackBtn.setOnClickListener {
            finish()
        }

        // 🔑 비밀번호 재설정 메일 보내기 버튼
        binding.realSigninBtn.setOnClickListener {
            val email = binding.editTextTextEmailAddress.text.toString().trim()

            // 이메일 형식 검증 (AuthValidator 재사용)
            val error = AuthValidator.validateEmail(email)
            if (error != null) {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase 비밀번호 재설정 메일 전송
            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(
                            this,
                            "비밀번호 재설정 메일을 전송했습니다.\n메일함을 확인해주세요.",
                            Toast.LENGTH_LONG
                        ).show()
                        // 필요하면 로그인 화면으로 돌아가기
                        finish()
                    } else {
                        // 가입되지 않은 이메일이거나 기타 오류
                        Toast.makeText(
                            this,
                            "해당 이메일로 가입된 계정을 찾을 수 없습니다.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
        }
    }
}