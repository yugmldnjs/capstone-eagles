package com.example.capstone

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.example.capstone.databinding.SettingTopBinding

// 설정의 프로필 사진 밑에 있는 줄을 없애는 용도
class line_delete(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedBelow = false

        // 설정 창에서 뒤로가기 버튼 눌렀을 때 메인 카메라 화면으로 되돌아가게함
        val binding = SettingTopBinding.bind(holder.itemView)
        binding.settingBackBtn.setOnClickListener {
            val intent = Intent(context, MainActivity2::class.java)
            context.startActivity(intent)
        }
    }
}