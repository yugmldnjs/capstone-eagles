package com.example.capstone

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.capstone.databinding.ActivityFindEmailBinding
import com.google.firebase.firestore.FirebaseFirestore

class FindEmailActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFindEmailBinding
    private lateinit var firestore: FirebaseFirestore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFindEmailBinding.inflate(layoutInflater)
        setContentView(binding.root)

        firestore = FirebaseFirestore.getInstance()

        binding.findEmailBtn.setOnClickListener {
            val nickname = binding.nicknameEditText.text.toString().trim()

            if (nickname.isEmpty()) {
                Toast.makeText(this, "닉네임을 입력해주세요.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // 'users' 컬렉션에서 'nickname' 필드가 일치하는 문서를 찾습니다.
            firestore.collection("users")
                .whereEqualTo("nickname", nickname)
                .get()
                .addOnSuccessListener { documents ->
                    if (documents.isEmpty) {
                        // 일치하는 닉네임이 없을 경우
                        Toast.makeText(this, "해당 닉네임으로 가입된 사용자를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                    } else {
                        // 닉네임이 일치하는 사용자를 찾았을 경우
                        val user = documents.first() // 첫 번째 검색 결과 사용
                        val email = user.getString("email")

                        // 결과 팝업창 표시
                        AlertDialog.Builder(this)
                            .setTitle("이메일 찾기 성공")
                            .setMessage("회원님의 이메일은\n'${email}'\n입니다.")
                            .setPositiveButton("확인") { _, _ -> finish() } // 확인 누르면 창 닫기
                            .show()
                    }
                }
                .addOnFailureListener { e ->
                    Toast.makeText(this, "이메일을 찾는 중 오류가 발생했습니다: ${e.message}", Toast.LENGTH_SHORT).show()
                }
        }
    }
}