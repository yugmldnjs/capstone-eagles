package com.example.capstone

import android.content.Intent
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.capstone.databinding.ActivityLogInBinding
import com.example.capstone.databinding.ActivityPasswordSearchBinding

class PasswordSearch : AppCompatActivity() {
    private lateinit var binding: ActivityPasswordSearchBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_password_search)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        binding = ActivityPasswordSearchBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.PWSerachBackBtn.setOnClickListener {
            finish()
        }
    }
}