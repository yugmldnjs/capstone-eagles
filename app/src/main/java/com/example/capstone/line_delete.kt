package com.example.capstone

import android.content.Context
import android.util.AttributeSet
import androidx.preference.Preference
import androidx.preference.PreferenceViewHolder

// 설정의 프로필 사진 밑에 있는 줄을 없애는 용도
class line_delete(context: Context, attrs: AttributeSet?) : Preference(context, attrs) {

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)
        holder.isDividerAllowedBelow = false
    }
}