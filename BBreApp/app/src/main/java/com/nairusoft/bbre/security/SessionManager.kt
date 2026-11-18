package com.nairusoft.bbre.security

import android.os.SystemClock

/**
 * Tracks the active authenticated user session using a monotonic clock
 * so backgrounding or device sleep counts toward the 5-minute timeout.
 */
class SessionManager(
    private val elapsedTimeProvider: () -> Long = { SystemClock.elapsedRealtime() },
    val timeoutMs: Long = DEFAULT_TIMEOUT_MS
) {

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L // 5 minutes
        const val KEY_SESSION_USERNAME = "session_username"
        const val KEY_LAST_LOGGED_IN_USER = "last_logged_in_user"

        @Volatile
        private var instance: SessionManager? = null

        fun getInstance(): SessionManager {
            return instance ?: synchronized(this) {
                instance ?: SessionManager().also { instance = it }
            }
        }
    }

    @Volatile
    var activeUsername: String? = null
        private set

    @Volatile
    var lastActivityElapsedMs: Long = 0L
        private set

    fun startSession(username: String) {
        if (username.isBlank()) return
        activeUsername = username
        lastActivityElapsedMs = elapsedTimeProvider()
    }

    fun updateActiveUsername(newUsername: String) {
        if (newUsername.isBlank()) return
        if (!activeUsername.isNullOrEmpty()) {
            activeUsername = newUsername
            lastActivityElapsedMs = elapsedTimeProvider()
        }
    }

    fun recordActivity() {
        if (!activeUsername.isNullOrEmpty()) {
            lastActivityElapsedMs = elapsedTimeProvider()
        }
    }

    fun isExpired(): Boolean {
        if (activeUsername.isNullOrEmpty()) return true
        val elapsed = elapsedTimeProvider() - lastActivityElapsedMs
        return elapsed < 0L || elapsed >= timeoutMs
    }

    fun isSessionActive(): Boolean {
        return !activeUsername.isNullOrEmpty() && !isExpired()
    }

    fun remainingTimeoutMs(): Long {
        if (activeUsername.isNullOrEmpty()) return 0L
        val elapsed = elapsedTimeProvider() - lastActivityElapsedMs
        if (elapsed < 0L) return 0L
        return (timeoutMs - elapsed).coerceAtLeast(0L)
    }

    fun clearSession() {
        activeUsername = null
        lastActivityElapsedMs = 0L
    }
}
