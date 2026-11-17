package com.nairusoft.bbre.data

import android.content.Context
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.UserKey
import com.nairusoft.bbre.security.KeyUtils
import com.nairusoft.bbre.security.StorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages CRUD operations and reactive [StateFlow] state for a user's saved payment keys.
 */
class KeysRepository(
    private val storageManager: StorageManager
) {

    private val _keys = MutableStateFlow<List<UserKey>>(emptyList())
    val keys: StateFlow<List<UserKey>> = _keys.asStateFlow()

    companion object {
        fun create(context: Context): KeysRepository {
            return KeysRepository(StorageManager.getInstance(context.applicationContext))
        }
    }

    suspend fun loadKeys(username: String): StorageLoadResult<List<UserKey>> {
        val result = storageManager.getKeysForUserResult(username)
        if (result is StorageLoadResult.Success) {
            _keys.value = result.data
        }
        return result
    }

    suspend fun addKey(
        type: String,
        value: String,
        description: String,
        username: String,
        timestamp: String,
        id: String = UUID.randomUUID().toString()
    ): StorageOpResult {
        val newKey = UserKey(
            id = id,
            type = type,
            value = value,
            username = username,
            createdBy = username,
            creationDate = timestamp,
            modificationDate = timestamp,
            version = "0.0.1",
            description = description
        )
        val result = storageManager.addKey(newKey)
        if (result is StorageOpResult.Success) {
            loadKeys(username)
        }
        return result
    }

    suspend fun updateKey(
        existingKey: UserKey,
        type: String,
        value: String,
        description: String,
        timestamp: String
    ): StorageOpResult {
        val updatedKey = existingKey.copy(
            type = type,
            value = value,
            description = description,
            modificationDate = timestamp,
            version = KeyUtils.incrementPatchVersion(existingKey.version)
        )
        val result = storageManager.updateKey(updatedKey)
        if (result is StorageOpResult.Success) {
            loadKeys(existingKey.username)
        }
        return result
    }

    suspend fun deleteKey(keyId: String, username: String): StorageOpResult {
        val result = storageManager.deleteKey(keyId)
        if (result is StorageOpResult.Success) {
            loadKeys(username)
        }
        return result
    }

    fun getBankOptions(customBankOption: String): List<String> {
        return KeyUtils.buildBankOptions(_keys.value, customBankOption)
    }

    fun generateUniqueCode(): String {
        return KeyUtils.generateUniqueAlphanumericCode(_keys.value.map { it.value })
    }

    fun clearKeys() {
        _keys.value = emptyList()
    }
}
