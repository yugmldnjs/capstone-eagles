package com.example.capstone

import android.util.Patterns

object AuthValidator {

    fun validateEmail(email: String): String? {
        if (email.isBlank()) {
            return "이메일을 입력해주세요."
        }
        if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            return "이메일 형식이 올바르지 않습니다."
        }
        return null
    }

    fun validatePassword(password: String): String? {
        if (password.isBlank()) {
            return "비밀번호를 입력해주세요."
        }
        if (password.length < 8) {
            return "비밀번호는 8자 이상이어야 합니다."
        }

        val hasLetter = password.any { it.isLetter() }
        val hasDigit = password.any { it.isDigit() }

        if (!hasLetter || !hasDigit) {
            return "비밀번호는 영문과 숫자를 모두 포함해야 합니다."
        }

        return null
    }

    fun validatePasswordWithConfirm(password: String, confirmPassword: String): String? {
        val baseError = validatePassword(password)
        if (baseError != null) return baseError

        if (password != confirmPassword) {
            return "비밀번호가 서로 일치하지 않습니다."
        }
        return null
    }
}
