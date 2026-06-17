package com.nairusoft.bbre.security

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class User(
    val username: String,
    val passwordHash: String
)

@Serializable
data class UserKey(
    val id: String,
    val type: String,
    val value: String,
    val username: String,
    val createdBy: String = "",
    val creationDate: String = "",
    val modificationDate: String = "",
    val version: String = "0.0.1",
    val description: String = ""
)

class StorageManager(private val context: Context) {

    companion object {
        private const val USERS_FILE = "usuarios.json"
        private const val KEYS_FILE = "llaves.json"
        private const val KEY_ALIAS = "bbre_db_key"

        @Volatile
        private var instance: StorageManager? = null

        fun getInstance(context: Context): StorageManager {
            return instance ?: synchronized(this) {
                instance ?: StorageManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val securityManager = SecurityManager.getInstance(context)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    init {
        securityManager.generateStandardKey(KEY_ALIAS)
    }

    // --- User Operations ---

    fun getUsers(): List<User> {
        val file = File(context.filesDir, USERS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val encryptedText = file.readText()
            val decryptedText = securityManager.decryptFromBase64(encryptedText, KEY_ALIAS) ?: return emptyList()
            json.decodeFromString<List<User>>(decryptedText)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun saveUsers(users: List<User>) {
        try {
            val file = File(context.filesDir, USERS_FILE)
            val jsonText = json.encodeToString(users)
            val encryptedText = securityManager.encryptToBase64(jsonText, KEY_ALIAS) ?: return
            file.writeText(encryptedText)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addUser(user: User): Boolean {
        val users = getUsers().toMutableList()
        if (users.any { it.username.equals(user.username, ignoreCase = true) }) {
            return false // User already exists
        }
        users.add(user)
        saveUsers(users)
        return true
    }

    fun updateUser(oldUsername: String, updatedUser: User): Boolean {
        val users = getUsers().toMutableList()
        val index = users.indexOfFirst { it.username.equals(oldUsername, ignoreCase = true) }
        if (index == -1) return false
        
        // If username is changing, ensure new one is unique
        if (!oldUsername.equals(updatedUser.username, ignoreCase = true) &&
            users.any { it.username.equals(updatedUser.username, ignoreCase = true) }) {
            return false
        }

        // Update corresponding keys owner if username changed
        if (!oldUsername.equals(updatedUser.username, ignoreCase = true)) {
            val keys = getKeys().toMutableList()
            var keysChanged = false
            for (i in keys.indices) {
                if (keys[i].username.equals(oldUsername, ignoreCase = true)) {
                    keys[i] = keys[i].copy(username = updatedUser.username)
                    keysChanged = true
                }
            }
            if (keysChanged) {
                saveKeys(keys)
            }
        }

        users[index] = updatedUser
        saveUsers(users)
        return true
    }

    fun deleteUser(username: String): Boolean {
        val users = getUsers().toMutableList()
        val index = users.indexOfFirst { it.username.equals(username, ignoreCase = true) }
        if (index == -1) return false
        users.removeAt(index)
        saveUsers(users)

        // Delete keys owned by user
        val keys = getKeys().filter { !it.username.equals(username, ignoreCase = true) }
        saveKeys(keys)
        return true
    }

    // --- Key Operations ---

    fun getKeys(): List<UserKey> {
        val file = File(context.filesDir, KEYS_FILE)
        if (!file.exists()) return emptyList()
        return try {
            val encryptedText = file.readText()
            val decryptedText = securityManager.decryptFromBase64(encryptedText, KEY_ALIAS) ?: return emptyList()
            json.decodeFromString<List<UserKey>>(decryptedText)
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    fun getKeysForUser(username: String): List<UserKey> {
        return getKeys().filter { it.username.equals(username, ignoreCase = true) }
    }

    fun saveKeys(keys: List<UserKey>) {
        try {
            val file = File(context.filesDir, KEYS_FILE)
            val jsonText = json.encodeToString(keys)
            val encryptedText = securityManager.encryptToBase64(jsonText, KEY_ALIAS) ?: return
            file.writeText(encryptedText)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun addKey(key: UserKey): Boolean {
        val keys = getKeys().toMutableList()
        if (keys.any { it.id == key.id || (it.type == key.type && it.value == key.value && it.username.equals(key.username, ignoreCase = true)) }) {
            return false // Key value must be unique for the user/system
        }
        keys.add(key)
        saveKeys(keys)
        return true
    }

    fun updateKey(updatedKey: UserKey): Boolean {
        val keys = getKeys().toMutableList()
        val index = keys.indexOfFirst { it.id == updatedKey.id }
        if (index == -1) return false
        
        // Ensure new key value/type is unique
        if (keys.any { it.id != updatedKey.id && it.type == updatedKey.type && it.value == updatedKey.value && it.username.equals(updatedKey.username, ignoreCase = true) }) {
            return false
        }

        keys[index] = updatedKey
        saveKeys(keys)
        return true
    }

    fun deleteKey(id: String): Boolean {
        val keys = getKeys().toMutableList()
        val index = keys.indexOfFirst { it.id == id }
        if (index == -1) return false
        keys.removeAt(index)
        saveKeys(keys)
        return true
    }
}
