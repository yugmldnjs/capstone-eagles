package com.example.capstone

import android.content.Context
import android.content.Intent
import android.util.AttributeSet
import androidx.fragment.app.FragmentActivity
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder
import com.example.capstone.databinding.SettingTopBinding

// 설정의 프로필 사진 밑에 있는 줄을 없애는 용도
class line_delete(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedBelow = false

        val binding = SettingTopBinding.bind(holder.itemView)

        // 설정 탭이 액티비티가 아니라 프래그먼트라서 intent 말고 이렇게 코드 작성함.
        binding.settingBackBtn.setOnClickListener {
            val activity = context as? FragmentActivity

            // 프래그먼트 뒤로가기 실행 (스택에서 하나 꺼내기)
            if (activity != null && activity.supportFragmentManager.backStackEntryCount > 0) {
                activity.supportFragmentManager.popBackStack()
            } else {
                activity?.finish()

            }
        }
    }
}