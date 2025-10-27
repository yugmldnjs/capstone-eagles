package com.example.capstone

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.capstone.databinding.ActivityLogInBinding
import com.example.capstone.databinding.ActivitySignInBinding
import android.text.method.PasswordTransformationMethod

class SignInActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySignInBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_sign_in)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding = ActivitySignInBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }


        binding.realSigninBtn.setOnClickListener {
            val intent = Intent(this, LogInActivity::class.java)
            startActivity(intent)
        }

// 비밀번호 보이기/숨기기 토글 버튼 리스너
        binding.imageButton4.setOnClickListener {
            // 현재 커서 위치 저장용
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