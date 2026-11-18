package com.nairusoft.bbre.data

import com.nairusoft.bbre.model.User
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.SessionManager

/**
 * Abstraction over credential hashing, encrypted preferences, and biometric enrollment
 * so repositories and ViewModels can be unit-tested on the JVM without Android Context.
 */
interface SecureAuthGateway {
    fun createUserCredentials(username: String, password: String): User
    fun verifyUserPassword(user: User, password: String): Boolean
    fun needsPasswordMigration(user: User): Boolean
    fun isBiometricAvailable(): Boolean
    fun isWeakBiometricOnly(): Boolean
    fun hasBiometricEnrollmentFor(username: String): Boolean
    fun clearBiometricEnrollment()
    fun getLastLoggedInUser(): String
    fun setLastLoggedInUser(username: String)
    fun clearLastLoggedInUser()
    fun getSessionUsername(): String
    fun setSessionUsername(username: String)
    fun clearSessionUsername()
}

/**
 * Android production implementation backed by [SecurityManager].
 */
class AndroidSecureAuthGateway(
    private val securityManager: SecurityManager
) : SecureAuthGateway {

    override fun createUserCredentials(username: String, password: String): User =
        securityManager.createUserCredentials(username, password)

    override fun verifyUserPassword(user: User, password: String): Boolean =
        securityManager.verifyUserPassword(user, password)

    override fun needsPasswordMigration(user: User): Boolean =
        securityManager.needsPasswordMigration(user)

    override fun isBiometricAvailable(): Boolean =
        securityManager.isBiometricAvailable()

    override fun isWeakBiometricOnly(): Boolean =
        securityManager.isWeakBiometricOnly()

    override fun hasBiometricEnrollmentFor(username: String): Boolean =
        securityManager.hasBiometricEnrollmentFor(username)

    override fun clearBiometricEnrollment() {
        securityManager.clearBiometricEnrollment()
    }

    override fun getLastLoggedInUser(): String =
        securityManager.getSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)

    override fun setLastLoggedInUser(username: String) {
        securityManager.storeSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER, username)
    }

    override fun clearLastLoggedInUser() {
        securityManager.removeSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
    }

    override fun getSessionUsername(): String =
        securityManager.getSecureData(SessionManager.KEY_SESSION_USERNAME)

    override fun setSessionUsername(username: String) {
        securityManager.storeSecureData(SessionManager.KEY_SESSION_USERNAME, username)
    }

    override fun clearSessionUsername() {
        securityManager.removeSecureData(SessionManager.KEY_SESSION_USERNAME)
    }
}
