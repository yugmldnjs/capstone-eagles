package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.text.method.PasswordTransformationMethod
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import com.example.capstone.databinding.ActivityLogInBinding
import com.google.firebase.auth.FirebaseAuth

class LogInActivity : AppCompatActivity() {
    private lateinit var binding: ActivityLogInBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        auth = FirebaseAuth.getInstance()
        binding = ActivityLogInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // X 버튼
        binding.settingsBackButton.setOnClickListener { finish() }

        // 회원가입 화면
        binding.signinBtn.setOnClickListener {
            startActivity(Intent(this, SignInActivity::class.java))
        }

        // 로그인 버튼
        binding.loginBtn.setOnClickListener {
            performLogin()
        }

        // 비밀번호 찾기
        binding.pwSearch.setOnClickListener {
            startActivity(Intent(this, PasswordSearch::class.java))
        }

        // 비밀번호 보이기/숨기기
        setupPasswordToggle()
    }

    override fun onStart() {
        super.onStart()
        val currentUser = auth.currentUser
        if (currentUser != null) {
            moveToMainScreen()
        }
    }

    private fun performLogin() {
        val email = binding.editTextTextEmailAddress.text.toString().trim()
        val password = binding.editTextTextPassword.text.toString().trim()

        // 이메일 형식 먼저 검증
        AuthValidator.validateEmail(email)?.let { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            return
        }

        if (password.isEmpty()) {
            Toast.makeText(this, "비밀번호를 입력해주세요.", Toast.LENGTH_SHORT).show()
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
                    val raw = task.exception?.message ?: ""
                    val lower = raw.lowercase()

                    val errorMessage = when {
                        // 등록되지 않은 이메일
                        lower.contains("no user record") ||
                                lower.contains("there is no user record") ->
                            "등록되지 않은 이메일입니다."

                        // 비밀번호 관련 에러 (길이/틀림 등 전부)
                        lower.contains("password") && lower.contains("invalid") ->
                            "비밀번호가 틀렸습니다."

                        // 이메일 형식 오류 (혹시 서버에서 잡히는 경우)
                        lower.contains("badly formatted") ->
                            "이메일 형식이 올바르지 않습니다."

                        else ->
                            "로그인에 실패했습니다. 이메일과 비밀번호를 다시 확인해주세요."
                    }

                    Toast.makeText(this, errorMessage, Toast.LENGTH_LONG).show()
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

    private fun moveToMainScreen() {
        val intent = Intent(this, MainActivity2::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}