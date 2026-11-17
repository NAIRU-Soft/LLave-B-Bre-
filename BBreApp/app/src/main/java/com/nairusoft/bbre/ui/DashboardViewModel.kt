package com.nairusoft.bbre.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nairusoft.bbre.data.AuthRepository
import com.nairusoft.bbre.data.KeysRepository
import com.nairusoft.bbre.model.AccountDeleteOutcome
import com.nairusoft.bbre.model.ProfileUpdateOutcome
import com.nairusoft.bbre.model.ReauthOutcome
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.UserKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for [DashboardActivity], holding reactive key state, session checks,
 * key CRUD mutations, and profile/account operations.
 */
class DashboardViewModel(
    private val authRepository: AuthRepository,
    private val keysRepository: KeysRepository,
    private val scopeOverride: CoroutineScope? = null
) : ViewModel() {

    private val activeScope: CoroutineScope
        get() = scopeOverride ?: viewModelScope

    private val _currentUser = MutableStateFlow("")
    val currentUser: StateFlow<String> = _currentUser.asStateFlow()

    val keys: StateFlow<List<UserKey>> = keysRepository.keys

    private val _errorMessages = Channel<String>(Channel.BUFFERED)
    val errorMessages = _errorMessages.receiveAsFlow()

    companion object {
        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                        return DashboardViewModel(
                            authRepository = AuthRepository.create(appContext),
                            keysRepository = KeysRepository.create(appContext)
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
        }
    }

    fun initializeActiveSession(): String? {
        val activeUser = authRepository.getValidatedActiveUsername()
        if (activeUser != null) {
            _currentUser.value = activeUser
        }
        return activeUser
    }

    fun isSessionValid(): Boolean {
        return authRepository.getValidatedActiveUsername() != null
    }

    fun recordUserActivity(): Long {
        authRepository.recordSessionActivity()
        return authRepository.remainingSessionTimeoutMs()
    }

    fun remainingTimeoutMs(): Long {
        return authRepository.remainingSessionTimeoutMs()
    }

    fun clearSessionAndInMemoryData() {
        authRepository.clearSession()
        _currentUser.value = ""
        keysRepository.clearKeys()
    }

    fun loadKeys() {
        val username = _currentUser.value
        if (username.isEmpty()) return
        activeScope.launch {
            when (val result = keysRepository.loadKeys(username)) {
                is StorageLoadResult.Success -> Unit
                is StorageLoadResult.Error -> _errorMessages.send(result.message)
            }
        }
    }

    fun getBankOptions(customBankOption: String): List<String> {
        return keysRepository.getBankOptions(customBankOption)
    }

    fun generateUniqueCode(): String {
        return keysRepository.generateUniqueCode()
    }

    fun saveKey(
        existingKey: UserKey?,
        type: String,
        value: String,
        description: String,
        timestamp: String,
        onResult: (StorageOpResult) -> Unit
    ) {
        val username = _currentUser.value
        activeScope.launch {
            val result = if (existingKey == null) {
                keysRepository.addKey(
                    type = type,
                    value = value,
                    description = description,
                    username = username,
                    timestamp = timestamp
                )
            } else {
                keysRepository.updateKey(
                    existingKey = existingKey,
                    type = type,
                    value = value,
                    description = description,
                    timestamp = timestamp
                )
            }
            onResult(result)
        }
    }

    fun deleteKey(keyId: String, onResult: (StorageOpResult) -> Unit) {
        val username = _currentUser.value
        activeScope.launch {
            val result = keysRepository.deleteKey(keyId, username)
            onResult(result)
        }
    }

    fun verifyCurrentPassword(enteredPassword: String, onResult: (ReauthOutcome) -> Unit) {
        val username = _currentUser.value
        activeScope.launch {
            onResult(authRepository.verifyCurrentPassword(username, enteredPassword))
        }
    }

    fun updateProfile(
        newUsername: String,
        newPassword: String,
        onResult: (ProfileUpdateOutcome) -> Unit
    ) {
        val current = _currentUser.value
        activeScope.launch {
            val outcome = authRepository.updateProfile(current, newUsername, newPassword)
            if (outcome is ProfileUpdateOutcome.Updated) {
                _currentUser.value = outcome.newUsername
            }
            onResult(outcome)
        }
    }

    fun deleteAccount(onResult: (AccountDeleteOutcome) -> Unit) {
        val username = _currentUser.value
        activeScope.launch {
            val outcome = authRepository.deleteAccount(username)
            if (outcome is AccountDeleteOutcome.Success) {
                _currentUser.value = ""
                keysRepository.clearKeys()
            }
            onResult(outcome)
        }
    }
}
