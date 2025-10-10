package com.example.capstone

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.capstone.databinding.ActivityFindPasswordBinding
import com.google.firebase.auth.FirebaseAuth

class FindPasswordActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFindPasswordBinding
    private lateinit var auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFindPasswordBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        binding.sendPasswordResetBtn.setOnClickListener {
            val email = binding.emailEditText.text.toString().trim()

            if (email.isEmpty()) {
                Toast.makeText(this, "이메일 주소를 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            auth.sendPasswordResetEmail(email)
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        // 이메일 발송 성공
                        AlertDialog.Builder(this)
                            .setTitle("전송 완료")
                            .setMessage("비밀번호 재설정 이메일을 보냈습니다.\n이메일함을 확인해주세요.")
                            .setPositiveButton("확인") { _, _ -> finish() }
                            .show()
                    } else {
                        // 이메일 발송 실패
                        Toast.makeText(this, "이메일 발송에 실패했습니다: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }
}