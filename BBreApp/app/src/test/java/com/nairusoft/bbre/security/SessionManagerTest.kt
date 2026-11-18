package com.nairusoft.bbre.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTest {

    @Test
    fun session_expiresAfterFiveMinutesAndClearsCleanly() {
        var nowMs = 10_000L
        val sessionManager = SessionManager(
            elapsedTimeProvider = { nowMs },
            timeoutMs = 5 * 60 * 1000L
        )

        assertFalse(sessionManager.isSessionActive())
        assertTrue(sessionManager.isExpired())

        sessionManager.startSession("ana")
        assertTrue(sessionManager.isSessionActive())
        assertEquals("ana", sessionManager.activeUsername)
        assertEquals(300_000L, sessionManager.remainingTimeoutMs())

        // 4m 59s later -> still active
        nowMs += 299_000L
        assertTrue(sessionManager.isSessionActive())
        assertFalse(sessionManager.isExpired())
        assertEquals(1_000L, sessionManager.remainingTimeoutMs())

        // Activity resets full 5-minute window
        sessionManager.recordActivity()
        assertEquals(300_000L, sessionManager.remainingTimeoutMs())

        // 5m 00s elapsed while in background/idle -> expired
        nowMs += 300_000L
        assertTrue(sessionManager.isExpired())
        assertFalse(sessionManager.isSessionActive())
        assertEquals(0L, sessionManager.remainingTimeoutMs())

        sessionManager.clearSession()
        assertNull(sessionManager.activeUsername)
        assertTrue(sessionManager.isExpired())
    }
}
