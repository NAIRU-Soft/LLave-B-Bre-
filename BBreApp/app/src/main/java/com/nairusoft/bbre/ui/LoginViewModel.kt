package com.nairusoft.bbre.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nairusoft.bbre.data.AuthRepository
import com.nairusoft.bbre.model.LoginEvent
import com.nairusoft.bbre.model.LoginUiState
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.security.AuthValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for [LoginActivity], managing auth form validation, registration,
 * password login, and post-biometric session verification.
 */
class LoginViewModel(
    private val authRepository: AuthRepository,
    private val scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val activeScope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    private val _uiState = MutableStateFlow(LoginUiState())
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _events = Channel<LoginEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(LoginViewModel::class.java)) {
                        return LoginViewModel(AuthRepository.create(appContext)) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    fun initializeScreen() {
        authRepository.clearSession()
        refreshBiometricStatus()
    }

    fun refreshBiometricStatus() {
        val bioStatus = authRepository.getBiometricUiStatus()
        _uiState.update { it.copy(biometricStatus = bioStatus) }
    }

    fun toggleMode() {
        val nextLoginMode = !_uiState.value.isLoginMode
        setMode(nextLoginMode)
    }

    fun setMode(isLoginMode: Boolean) {
        val bioStatus = if (isLoginMode) authRepository.getBiometricUiStatus() else _uiState.value.biometricStatus
        _uiState.update {
            it.copy(
                isLoginMode = isLoginMode,
                usernameError = null,
                passwordError = null,
                isUsernameTaken = false,
                isInvalidCredentials = false,
                biometricStatus = bioStatus
            )
        }
    }

    fun onUsernameChanged() {
        if (_uiState.value.usernameError != null ||
            _uiState.value.isUsernameTaken ||
            _uiState.value.isInvalidCredentials
        ) {
            _uiState.update {
                it.copy(
                    usernameError = null,
                    isUsernameTaken = false,
                    isInvalidCredentials = false
                )
            }
        }
    }

    fun onPasswordChanged() {
        if (_uiState.value.passwordError != null || _uiState.value.isInvalidCredentials) {
            _uiState.update {
                it.copy(
                    passwordError = null,
                    isInvalidCredentials = false
                )
            }
        }
    }

    fun submitCredentials(rawUsername: String, password: String) {
        val username = rawUsername.trim()
        val usernameErr = AuthValidator.validateUsername(username)
        val passwordErr = AuthValidator.validatePassword(password)

        if (usernameErr != null || passwordErr != null) {
            _uiState.update {
                it.copy(
                    usernameError = usernameErr,
                    passwordError = passwordErr,
                    isUsernameTaken = false,
                    isInvalidCredentials = false
                )
            }
            return
        }

        _uiState.update {
            it.copy(
                isLoading = true,
                usernameError = null,
                passwordError = null,
                isUsernameTaken = false,
                isInvalidCredentials = false
            )
        }

        activeScope.launch {
            if (_uiState.value.isLoginMode) {
                when (val outcome = authRepository.login(username, password)) {
                    is AuthRepository.LoginOutcome.Success -> {
                        _uiState.update { it.copy(isLoading = false) }
                        if (outcome.offerBiometricEnrollment) {
                            _events.send(LoginEvent.PromptBiometricEnrollment(outcome.username))
                        } else {
                            _events.send(LoginEvent.NavigateToDashboard)
                        }
                    }
                    is AuthRepository.LoginOutcome.InvalidCredentials -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isInvalidCredentials = true
                            )
                        }
                    }
                    is AuthRepository.LoginOutcome.Error -> {
                        _uiState.update { it.copy(isLoading = false) }
                        _events.send(LoginEvent.ShowMessage(outcome.message))
                    }
                }
            } else {
                when (val result = authRepository.register(username, password)) {
                    is StorageOpResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isLoginMode = true,
                                usernameError = null,
                                passwordError = null,
                                isUsernameTaken = false,
                                biometricStatus = authRepository.getBiometricUiStatus()
                            )
                        }
                        _events.send(LoginEvent.RegistrationSucceeded)
                    }
                    is StorageOpResult.Duplicate -> {
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                isUsernameTaken = true
                            )
                        }
                    }
                    is StorageOpResult.Error -> {
                        _uiState.update { it.copy(isLoading = false) }
                        _events.send(LoginEvent.ShowMessage(result.message))
                    }
                    is StorageOpResult.NotFound -> {
                        _uiState.update { it.copy(isLoading = false) }
                    }
                }
            }
        }
    }

    fun completeBiometricLogin(decryptedUser: String) {
        activeScope.launch {
            when (val outcome = authRepository.completeBiometricLogin(decryptedUser)) {
                is AuthRepository.BiometricLoginOutcome.Success -> {
                    _events.send(LoginEvent.BiometricLoginSucceeded)
                }
                is AuthRepository.BiometricLoginOutcome.UserMissing -> {
                    _uiState.update {
                        it.copy(biometricStatus = authRepository.getBiometricUiStatus())
                    }
                    _events.send(LoginEvent.BiometricUserMissing)
                }
                is AuthRepository.BiometricLoginOutcome.Error -> {
                    _events.send(LoginEvent.BiometricLoadError(outcome.message))
                }
            }
        }
    }
}
