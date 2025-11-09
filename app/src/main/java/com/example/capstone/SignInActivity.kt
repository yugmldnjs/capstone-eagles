package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.capstone.databinding.ActivitySignInBinding
import com.google.firebase.auth.FirebaseAuth
import android.text.method.PasswordTransformationMethod

class SignInActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignInBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Firebase Auth 초기화
        auth = FirebaseAuth.getInstance()

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // 뒤로가기 버튼
        binding.SignInBackBtn.setOnClickListener { finish() }

        // 회원가입 버튼 클릭
        binding.realSigninBtn.setOnClickListener {
            val email = binding.editTextTextEmailAddress.text.toString().trim()
            val password = binding.editTextTextPassword.text.toString().trim()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Firebase에 회원가입 요청
            auth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this) { task ->
                    if (task.isSuccessful) {
                        Toast.makeText(this, "회원가입 성공! 자동 로그인 중...", Toast.LENGTH_SHORT).show()
                        // 로그인 화면으로 이동
                        startActivity(Intent(this, LogInActivity::class.java))
                        finish()
                    } else {
                        val error = task.exception?.message ?: "회원가입 실패"
                        Toast.makeText(this, "회원가입 실패: $error", Toast.LENGTH_LONG).show()
                    }
                }
        }

        // 비밀번호 표시/숨기기
        binding.imageButton4.setOnClickListener {
            val cursorPosition = binding.editTextTextPassword.selectionEnd
            if (binding.editTextTextPassword.transformationMethod == PasswordTransformationMethod.getInstance()) {
                binding.editTextTextPassword.transformationMethod = null
                binding.imageButton4.setImageResource(R.drawable.show_password)
            } else {
                binding.editTextTextPassword.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.imageButton4.setImageResource(R.drawable.hide_password)
            }
            binding.editTextTextPassword.setSelection(cursorPosition)
        }
    }
}
