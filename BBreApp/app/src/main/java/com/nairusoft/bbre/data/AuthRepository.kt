package com.nairusoft.bbre.data

import android.content.Context
import com.nairusoft.bbre.model.AccountDeleteOutcome
import com.nairusoft.bbre.model.BiometricBannerMode
import com.nairusoft.bbre.model.BiometricUiStatus
import com.nairusoft.bbre.model.ProfileUpdateOutcome
import com.nairusoft.bbre.model.ReauthOutcome
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.SessionManager
import com.nairusoft.bbre.security.StorageManager

/**
 * Coordinates user authentication, transparent password KDF migration,
 * biometric enrollment checks, session lifecycle, and profile updates.
 */
class AuthRepository(
    private val storageManager: StorageManager,
    private val authGateway: SecureAuthGateway,
    private val sessionManager: SessionManager = SessionManager.getInstance()
) {

    sealed class LoginOutcome {
        data class Success(val username: String, val offerBiometricEnrollment: Boolean) : LoginOutcome()
        object InvalidCredentials : LoginOutcome()
        data class Error(val message: String) : LoginOutcome()
    }

    sealed class BiometricLoginOutcome {
        data class Success(val username: String) : BiometricLoginOutcome()
        object UserMissing : BiometricLoginOutcome()
        data class Error(val message: String) : BiometricLoginOutcome()
    }

    companion object {
        fun create(context: Context): AuthRepository {
            val appContext = context.applicationContext
            return AuthRepository(
                storageManager = StorageManager.getInstance(appContext),
                authGateway = AndroidSecureAuthGateway(SecurityManager.getInstance(appContext)),
                sessionManager = SessionManager.getInstance()
            )
        }
    }

    fun clearSession() {
        sessionManager.clearSession()
        authGateway.clearSessionUsername()
    }

    fun getValidatedActiveUsername(): String? {
        val storedUser = authGateway.getSessionUsername()
        val active = sessionManager.isSessionActive()
        val matchesStored = storedUser.isNotEmpty() &&
            sessionManager.activeUsername.equals(storedUser, ignoreCase = true)
        return if (active && matchesStored) storedUser else null
    }

    fun recordSessionActivity() {
        sessionManager.recordActivity()
    }

    fun remainingSessionTimeoutMs(): Long {
        return sessionManager.remainingTimeoutMs()
    }

    fun getBiometricUiStatus(): BiometricUiStatus {
        val savedUser = authGateway.getLastLoggedInUser()
        return when {
            savedUser.isNotEmpty() && authGateway.hasBiometricEnrollmentFor(savedUser) -> {
                BiometricUiStatus(BiometricBannerMode.STRONG_AVAILABLE, savedUser)
            }
            savedUser.isNotEmpty() && authGateway.isWeakBiometricOnly() -> {
                BiometricUiStatus(BiometricBannerMode.WEAK_ONLY, savedUser)
            }
            else -> BiometricUiStatus(BiometricBannerMode.HIDDEN, "")
        }
    }

    suspend fun login(username: String, password: String): LoginOutcome {
        return when (val usersResult = storageManager.getUsersResult()) {
            is StorageLoadResult.Error -> LoginOutcome.Error(usersResult.message)
            is StorageLoadResult.Success -> {
                val foundUser = usersResult.data.find { it.username.equals(username, ignoreCase = true) }
                if (foundUser != null && authGateway.verifyUserPassword(foundUser, password)) {
                    // Migrate legacy unsalted SHA-256 user to salted PBKDF2 on first successful login
                    if (authGateway.needsPasswordMigration(foundUser)) {
                        val migratedUser = authGateway.createUserCredentials(foundUser.username, password)
                        storageManager.updateUser(foundUser.username, migratedUser)
                    }

                    sessionManager.startSession(foundUser.username)
                    authGateway.setSessionUsername(foundUser.username)
                    authGateway.setLastLoggedInUser(foundUser.username)

                    if (!authGateway.hasBiometricEnrollmentFor(foundUser.username)) {
                        authGateway.clearBiometricEnrollment()
                    }

                    val offerEnroll = authGateway.isBiometricAvailable() &&
                        !authGateway.hasBiometricEnrollmentFor(foundUser.username)
                    LoginOutcome.Success(foundUser.username, offerEnroll)
                } else {
                    LoginOutcome.InvalidCredentials
                }
            }
        }
    }

    suspend fun register(username: String, password: String): StorageOpResult {
        val newUser = authGateway.createUserCredentials(username, password)
        return storageManager.addUser(newUser)
    }

    suspend fun completeBiometricLogin(decryptedUser: String): BiometricLoginOutcome {
        return when (val usersResult = storageManager.getUsersResult()) {
            is StorageLoadResult.Error -> BiometricLoginOutcome.Error(usersResult.message)
            is StorageLoadResult.Success -> {
                val existingUser = usersResult.data.find {
                    it.username.equals(decryptedUser, ignoreCase = true)
                }
                if (existingUser != null) {
                    sessionManager.startSession(existingUser.username)
                    authGateway.setSessionUsername(existingUser.username)
                    BiometricLoginOutcome.Success(existingUser.username)
                } else {
                    authGateway.clearBiometricEnrollment()
                    BiometricLoginOutcome.UserMissing
                }
            }
        }
    }

    suspend fun verifyCurrentPassword(username: String, enteredPassword: String): ReauthOutcome {
        return when (val loadResult = storageManager.getUsersResult()) {
            is StorageLoadResult.Error -> ReauthOutcome.Error(loadResult.message)
            is StorageLoadResult.Success -> {
                val user = loadResult.data.find { it.username.equals(username, ignoreCase = true) }
                if (user != null && authGateway.verifyUserPassword(user, enteredPassword)) {
                    ReauthOutcome.Verified
                } else {
                    ReauthOutcome.WrongPassword
                }
            }
        }
    }

    suspend fun updateProfile(
        currentUsername: String,
        newUsername: String,
        newPassword: String
    ): ProfileUpdateOutcome {
        val users = when (val load = storageManager.getUsersResult()) {
            is StorageLoadResult.Success -> load.data
            is StorageLoadResult.Error -> return ProfileUpdateOutcome.Error(load.message)
        }

        val foundUser = users.find { it.username.equals(currentUsername, ignoreCase = true) }
            ?: return ProfileUpdateOutcome.UserMissing

        if (newUsername == foundUser.username && newPassword.isEmpty()) {
            return ProfileUpdateOutcome.NoChanges
        }

        val updatedUser = if (newPassword.isNotEmpty()) {
            authGateway.createUserCredentials(newUsername, newPassword)
        } else {
            // Preserve historical createdBy on existing keys (D-04)
            foundUser.copy(username = newUsername)
        }

        return when (val updateResult = storageManager.updateUser(currentUsername, updatedUser)) {
            is StorageOpResult.Success -> {
                val usernameChanged = !currentUsername.equals(newUsername, ignoreCase = true)
                sessionManager.updateActiveUsername(newUsername)
                authGateway.setSessionUsername(newUsername)
                authGateway.setLastLoggedInUser(newUsername)
                if (usernameChanged) {
                    authGateway.clearBiometricEnrollment()
                }
                ProfileUpdateOutcome.Updated(newUsername)
            }
            is StorageOpResult.Duplicate -> ProfileUpdateOutcome.DuplicateUsername
            is StorageOpResult.NotFound -> ProfileUpdateOutcome.UpdateNotFound
            is StorageOpResult.Error -> ProfileUpdateOutcome.Error(updateResult.message)
        }
    }

    suspend fun deleteAccount(username: String): AccountDeleteOutcome {
        return when (val result = storageManager.deleteUser(username)) {
            is StorageOpResult.Success -> {
                authGateway.clearBiometricEnrollment()
                authGateway.clearLastLoggedInUser()
                clearSession()
                AccountDeleteOutcome.Success
            }
            is StorageOpResult.NotFound -> AccountDeleteOutcome.NotFound
            is StorageOpResult.Error -> AccountDeleteOutcome.Error(result.message)
            is StorageOpResult.Duplicate -> AccountDeleteOutcome.NotFound
        }
    }
}
