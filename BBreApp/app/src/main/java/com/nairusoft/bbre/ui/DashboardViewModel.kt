package com.nairusoft.bbre.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nairusoft.bbre.data.AuthRepository
import com.nairusoft.bbre.data.KeysRepository
import com.nairusoft.bbre.model.AccountDeleteOutcome
import com.nairusoft.bbre.model.BackupPayload
import com.nairusoft.bbre.model.KeyImportSummary
import com.nairusoft.bbre.model.ProfileUpdateOutcome
import com.nairusoft.bbre.model.ReauthOutcome
import com.nairusoft.bbre.model.RestoreAccountResult
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.User
import com.nairusoft.bbre.model.UserKey
import com.nairusoft.bbre.security.BackupCryptoManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Arrays

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

    data class EncryptedExportBundle(
        val encryptedJson: String,
        val keyCount: Int
    )

    data class BackupDecryptionPreview(
        val payload: BackupPayload,
        val isSameUser: Boolean,
        val backupUserAlreadyExistsLocally: Boolean
    )

    fun exportEncryptedBackup(
        backupPassword: CharArray,
        appVersion: String,
        exportedAt: String,
        onResult: (Result<EncryptedExportBundle>) -> Unit
    ) {
        val username = _currentUser.value
        activeScope.launch {
            try {
                if (username.isEmpty()) {
                    onResult(Result.failure(IllegalStateException("Sesión no iniciada")))
                    return@launch
                }

                val userRecord = when (val userLoad = authRepository.getUserRecord(username)) {
                    is StorageLoadResult.Success -> userLoad.data
                        ?: run {
                            onResult(Result.failure(IllegalStateException("No se encontró el usuario actual")))
                            return@launch
                        }
                    is StorageLoadResult.Error -> {
                        onResult(Result.failure(userLoad.cause))
                        return@launch
                    }
                }

                val keysList = when (val keysLoad = keysRepository.getKeysSnapshot(username)) {
                    is StorageLoadResult.Success -> keysLoad.data
                    is StorageLoadResult.Error -> {
                        onResult(Result.failure(keysLoad.cause))
                        return@launch
                    }
                }

                val payload = BackupPayload(
                    schemaVersion = 1,
                    appVersion = appVersion,
                    exportedAt = exportedAt,
                    user = userRecord,
                    keys = keysList
                )

                val encryptedResult = withContext(Dispatchers.Default) {
                    BackupCryptoManager.encryptToJson(payload, backupPassword)
                }

                onResult(encryptedResult.map { encrypted ->
                    EncryptedExportBundle(encryptedJson = encrypted, keyCount = keysList.size)
                })
            } finally {
                Arrays.fill(backupPassword, '\u0000')
            }
        }
    }

    fun decryptBackupPreview(
        envelopeJson: String,
        backupPassword: CharArray,
        onResult: (Result<BackupDecryptionPreview>) -> Unit
    ) {
        val activeUsername = _currentUser.value
        activeScope.launch {
            try {
                val payload = withContext(Dispatchers.Default) {
                    BackupCryptoManager.decryptFromJson(envelopeJson, backupPassword)
                }.getOrElse { error ->
                    onResult(Result.failure(error))
                    return@launch
                }

                val existsLocally = when (val existsLoad = authRepository.usernameExists(payload.user.username)) {
                    is StorageLoadResult.Success -> existsLoad.data
                    is StorageLoadResult.Error -> {
                        onResult(Result.failure(existsLoad.cause))
                        return@launch
                    }
                }

                val sameUser = payload.user.username.equals(activeUsername, ignoreCase = true)
                onResult(
                    Result.success(
                        BackupDecryptionPreview(
                            payload = payload,
                            isSameUser = sameUser,
                            backupUserAlreadyExistsLocally = existsLocally
                        )
                    )
                )
            } finally {
                Arrays.fill(backupPassword, '\u0000')
            }
        }
    }

    fun importBackupKeysIntoCurrentUser(
        incomingKeys: List<UserKey>,
        onResult: (Result<KeyImportSummary>) -> Unit
    ) {
        val username = _currentUser.value
        activeScope.launch {
            onResult(keysRepository.importKeys(username, incomingKeys))
        }
    }

    fun restoreBackedUpAccount(
        user: User,
        incomingKeys: List<UserKey>,
        onResult: (RestoreAccountResult) -> Unit
    ) {
        val username = _currentUser.value
        activeScope.launch {
            val result = authRepository.restoreAccountWithKeys(user, incomingKeys)
            if (result is RestoreAccountResult.Restored && user.username.equals(username, ignoreCase = true)) {
                keysRepository.loadKeys(username)
            }
            onResult(result)
        }
    }
}
