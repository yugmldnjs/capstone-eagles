package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.capstone.databinding.ActivityLogInBinding
import com.google.firebase.auth.FirebaseAuth

class LogInActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogInBinding
    private lateinit var auth: FirebaseAuth;

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Firebase Auth 초기화
        auth = FirebaseAuth.getInstance()

        binding = ActivityLogInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        // X 버튼 (닫기)
        binding.settingsBackButton.setOnClickListener {
            finish()
        }

        // 회원가입 버튼
        binding.signinBtn.setOnClickListener {
            val intent = Intent(this, SignInActivity::class.java)
            startActivity(intent)
        }

        // 로그인 버튼
        binding.loginBtn.setOnClickListener {
            performLogin()
        }

        // 비밀번호 찾기 버튼
        binding.button3.setOnClickListener {
            resetPassword()
        }
    }

    override fun onStart() {
        super.onStart()
        // 이미 로그인되어 있는지 확인
        val currentUser = auth.currentUser
        if (currentUser != null) {
            // 이미 로그인되어 있으면 메인 화면으로 바로 이동
            moveToMainScreen()
        }
    }

    private fun performLogin() {
        val email = binding.editTextTextEmailAddress.text.toString().trim()
        val password = binding.editTextTextPassword.text.toString().trim()

        if (email.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, "이메일과 비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // 로그인 버튼 비활성화 (중복 클릭 방지)
        binding.loginBtn.isEnabled = false

        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                binding.loginBtn.isEnabled = true

                if (task.isSuccessful) {
                    Toast.makeText(this, "로그인에 성공했습니다.", Toast.LENGTH_SHORT).show()
                    moveToMainScreen()
                } else {
                    val errorMessage = when {
                        task.exception?.message?.contains("no user record") == true ->
                            "등록되지 않은 이메일입니다."
                        task.exception?.message?.contains("password is invalid") == true ->
                            "비밀번호가 올바르지 않습니다."
                        task.exception?.message?.contains("badly formatted") == true ->
                            "이메일 형식이 올바르지 않습니다."
                        else -> "로그인 실패: ${task.exception?.message}"
                    }
                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
                }
            }
    }

    private fun resetPassword() {
        val email = binding.editTextTextEmailAddress.text.toString().trim()

        if (email.isEmpty()) {
            Toast.makeText(this, "비밀번호를 재설정할 이메일을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        auth.sendPasswordResetEmail(email)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Toast.makeText(
                        this,
                        "비밀번호 재설정 이메일을 보냈습니다. 이메일을 확인해주세요.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    Toast.makeText(
                        this,
                        "이메일 전송 실패: ${task.exception?.message}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
    }

    private fun moveToMainScreen() {
        val intent = Intent(this, MainActivity2::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}