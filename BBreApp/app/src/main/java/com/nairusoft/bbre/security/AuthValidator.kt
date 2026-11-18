package com.nairusoft.bbre.security

/**
 * Pure-JVM form validation helpers shared between Login, Registration, and Profile Settings.
 */
object AuthValidator {

    const val MIN_PASSWORD_LENGTH = 8

    enum class UsernameError {
        EMPTY,
        CONTAINS_WHITESPACE
    }

    enum class PasswordError {
        EMPTY,
        CONTAINS_WHITESPACE,
        TOO_SHORT
    }

    fun validateUsername(username: String): UsernameError? {
        return when {
            username.isEmpty() -> UsernameError.EMPTY
            username.any { it.isWhitespace() } -> UsernameError.CONTAINS_WHITESPACE
            else -> null
        }
    }

    fun validatePassword(password: String): PasswordError? {
        return when {
            password.isEmpty() -> PasswordError.EMPTY
            password.any { it.isWhitespace() } -> PasswordError.CONTAINS_WHITESPACE
            password.length < MIN_PASSWORD_LENGTH -> PasswordError.TOO_SHORT
            else -> null
        }
    }
}
