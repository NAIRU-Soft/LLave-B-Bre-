package com.nairusoft.bbre.model

import com.nairusoft.bbre.security.AuthValidator

/**
 * Display state for the biometric section on the login screen.
 */
enum class BiometricBannerMode {
    STRONG_AVAILABLE,
    WEAK_ONLY,
    HIDDEN
}

/**
 * Biometric readiness snapshot bound to the last logged-in user.
 */
data class BiometricUiStatus(
    val mode: BiometricBannerMode = BiometricBannerMode.HIDDEN,
    val savedUsername: String = ""
)

/**
 * Immutable UI state for [com.nairusoft.bbre.ui.LoginActivity].
 */
data class LoginUiState(
    val isLoginMode: Boolean = true,
    val isLoading: Boolean = false,
    val usernameError: AuthValidator.UsernameError? = null,
    val passwordError: AuthValidator.PasswordError? = null,
    val isUsernameTaken: Boolean = false,
    val isInvalidCredentials: Boolean = false,
    val biometricStatus: BiometricUiStatus = BiometricUiStatus()
)

/**
 * One-shot navigation or dialog events emitted by [com.nairusoft.bbre.ui.LoginViewModel].
 */
sealed class LoginEvent {
    object NavigateToDashboard : LoginEvent()
    data class PromptBiometricEnrollment(val username: String) : LoginEvent()
    object RegistrationSucceeded : LoginEvent()
    data class ShowMessage(val message: String) : LoginEvent()
    object BiometricLoginSucceeded : LoginEvent()
    object BiometricUserMissing : LoginEvent()
    data class BiometricLoadError(val message: String) : LoginEvent()
}

/**
 * Immutable UI state for the Keys tab inside [com.nairusoft.bbre.ui.DashboardActivity].
 */
data class DashboardKeysUiState(
    val isLoading: Boolean = false,
    val keys: List<UserKey> = emptyList(),
    val currentUser: String = ""
)

/**
 * Result of verifying the user's current password before sensitive actions.
 */
sealed class ReauthOutcome {
    object Verified : ReauthOutcome()
    object WrongPassword : ReauthOutcome()
    data class Error(val message: String) : ReauthOutcome()
}

/**
 * Result of updating username and/or password from Settings.
 */
sealed class ProfileUpdateOutcome {
    data class Updated(val newUsername: String) : ProfileUpdateOutcome()
    object NoChanges : ProfileUpdateOutcome()
    object DuplicateUsername : ProfileUpdateOutcome()
    object UserMissing : ProfileUpdateOutcome()
    object UpdateNotFound : ProfileUpdateOutcome()
    data class Error(val message: String) : ProfileUpdateOutcome()
}

/**
 * Result of deleting the current user account and its associated keys.
 */
sealed class AccountDeleteOutcome {
    object Success : AccountDeleteOutcome()
    object NotFound : AccountDeleteOutcome()
    data class Error(val message: String) : AccountDeleteOutcome()
}
