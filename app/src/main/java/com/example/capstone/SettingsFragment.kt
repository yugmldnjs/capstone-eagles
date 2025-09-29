package com.example.capstone

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat

class SettingsFragment : PreferenceFragmentCompat() {

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        // preferences_setting.xml 파일을 화면에 표시
        setPreferencesFromResource(R.xml.preferences_setting, rootKey)
    }
}