package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.capstone.databinding.ActivitySignInBinding
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import android.text.method.PasswordTransformationMethod

class SignInActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignInBinding
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // 뒤로가기
        binding.SignInBackBtn.setOnClickListener { finish() }

        // 비밀번호 표시/숨기기
        setupPasswordToggle()

        // 회원가입 버튼
        binding.realSigninBtn.setOnClickListener {
            performSignUp()
        }
    }

    private fun performSignUp() {
        val email = binding.editTextTextEmailAddress.text.toString().trim()
        val password = binding.editTextTextPassword.text.toString().trim()
        val confirmPassword = binding.editTextTextPasswordConfirm.text.toString().trim()
        val nickname = binding.editTextText.text.toString().trim()

        // 이메일 검증
        AuthValidator.validateEmail(email)?.let { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        // 비밀번호 + 확인 검증 (영문+숫자, 6자 이상, 일치 여부)
        AuthValidator.validatePasswordWithConfirm(password, confirmPassword)?.let { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        // 닉네임 검증 (원하면 선택 사항으로 바꿀 수 있음)
        if (nickname.isBlank()) {
            Toast.makeText(this, "닉네임을 입력해주세요.", Toast.LENGTH_SHORT).show()
            return
        }

        // Firebase Auth 회원가입
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    val error = task.exception?.message ?: "회원가입 실패"
                    Toast.makeText(this, "회원가입 실패: $error", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }

                val user = auth.currentUser ?: run {
                    Toast.makeText(this, "회원 정보가 없습니다.", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }

                // Firestore 유저 정보 저장 (닉네임 포함)
                val userData = hashMapOf(
                    "uid" to user.uid,
                    "email" to email,
                    "nickname" to nickname,
                    "createdAt" to System.currentTimeMillis()
                )

                db.collection("users").document(user.uid)
                    .set(userData)
                    .addOnSuccessListener {
                        // ✅ 회원가입 직후 자동 로그아웃
                        auth.signOut()

                        Toast.makeText(this, "회원가입 성공! 로그인 해주세요.", Toast.LENGTH_SHORT).show()

                        // 로그인 화면으로 이동 (뒤로가기로 다시 못 돌아오게 스택 정리)
                        val intent = Intent(this, LogInActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        }
                        startActivity(intent)
                    }
                    .addOnFailureListener { e ->
                        Toast.makeText(this, "DB 저장 실패: ${e.message}", Toast.LENGTH_LONG).show()
                    }
            }
    }

    private fun setupPasswordToggle() {
        binding.imageButton4.setOnClickListener {
            val edit = binding.editTextTextPassword
            val cursorPosition = edit.selectionEnd

            if (edit.transformationMethod == PasswordTransformationMethod.getInstance()) {
                edit.transformationMethod = null
                binding.imageButton4.setImageResource(R.drawable.show_password)
            } else {
                edit.transformationMethod = PasswordTransformationMethod.getInstance()
                binding.imageButton4.setImageResource(R.drawable.hide_password)
            }

            edit.setSelection(cursorPosition)
        }
    }
}
