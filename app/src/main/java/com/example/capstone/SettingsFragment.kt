package com.example.capstone

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

// 설정창에서 기능넣고 싶으면 이 액티비티에서
class SettingsFragment : PreferenceFragmentCompat() {
    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // preferences_setting.xml 파일을 화면에 표시
        setPreferencesFromResource(R.xml.preferences_setting, rootKey)

        // Firebase Auth 인스턴스 초기화
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        // 유저 정보(닉네임, 이메일 확인)
        val userInfoPreference: Preference? = findPreference("user_info")

        userInfoPreference?.setOnPreferenceClickListener {
            val user = auth.currentUser
            val uid = user?.uid

            if (uid != null) {
                // DB에서 데이터 가져오기 ("users" 컬렉션)
                db.collection("users").document(uid).get()
                    .addOnSuccessListener { document ->
                        if (document != null && document.exists()) {
                            val nickname = document.getString("nickname") ?: "닉네임 없음"
                            val email = user.email ?: "이메일 정보 없음"

                            // 팝업창 띄우기
                            showUserInfoDialog(nickname, email)
                        } else {
                            Toast.makeText(requireContext(), "회원 정보를 찾을 수 없습니다.", Toast.LENGTH_SHORT).show()
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(requireContext(), "정보를 불러오는데 실패했습니다.", Toast.LENGTH_SHORT).show()
                    }
            } else {
                Toast.makeText(requireContext(), "로그인 정보가 없습니다.", Toast.LENGTH_SHORT).show()
            }
            true
        }


        // '로그아웃' Preference 객체를 key 값으로 찾습니다.
        val logoutPreference: Preference? = findPreference("log_out")

        // 로그아웃 버튼에 클릭 리스너를 설정합니다.
        logoutPreference?.setOnPreferenceClickListener {
            // 팝업창(AlertDialog)을 생성합니다.
            AlertDialog.Builder(requireContext())
                .setTitle("로그아웃") // 팝업창 제목
                .setMessage("정말 로그아웃 하시겠습니까?") // 팝업창 메시지
                .setPositiveButton("확인") { _, _ ->
                    // '확인' 버튼을 눌렀을 때 실행될 코드

                    // 1. Firebase에서 로그아웃
                    auth.signOut()

                    // 2. 사용자에게 로그아웃 알림
                    Toast.makeText(requireContext(), "로그아웃 되었습니다.", Toast.LENGTH_SHORT).show()

                    // 3. 로그인 화면으로 이동
                    val intent = Intent(requireContext(), LogInActivity::class.java)

                    // 4. 뒤로가기 버튼을 눌러도 이전 화면으로 돌아가지 않도록 스택을 비웁니다.
                    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    startActivity(intent)

                    // 5. 현재 설정 화면을 포함한 Activity를 종료합니다.
                    requireActivity().finish()
                }
                .setNegativeButton("취소", null) // '취소' 버튼을 누르면 아무 동작 없이 팝업창을 닫습니다.
                .show()

            true // 클릭 이벤트를 처리했음을 시스템에 알립니다.
        }
    }
    private fun showUserInfoDialog(nickname: String, email: String) {
        AlertDialog.Builder(requireContext())
            .setTitle("유저 정보") // 창 제목
            .setMessage("닉네임: $nickname\n이메일: $email") // 내용
            .setPositiveButton("확인") { dialog, _ ->
                dialog.dismiss() // 닫기
            }
            .show()
    }
}