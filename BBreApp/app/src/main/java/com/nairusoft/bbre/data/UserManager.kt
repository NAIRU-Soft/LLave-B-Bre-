package com.nairusoft.bbre.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.UUID

/**
 * UserManager - Handles user registration, authentication and profile management
 * Stores data in encrypted files: usuarios.json (users) and llaves.json (keys)
 */
class UserManager private constructor(private val context: Context) {
    
    companion object {
        private const val PREF_NAME = "bbre_user_manager"
        private const val KEY_USERS = "usuarios_json"
        private const val KEY_KEYS = "llaves_json"
        
        @Volatile
        private var instance: UserManager? = null
        
        fun getInstance(context: Context): UserManager {
            return instance ?: synchronized(this) {
                instance ?: UserManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
    
    private val masterKey: MasterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }
    
    private val encryptedPrefs: EncryptedSharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }
    
    private val gson = Gson()
    
    // Data classes
    data class User(val username: String, val passwordHash: String)
    data class Key(val id: String, val userId: String, val type: String, val value: String)
    
    // Get all users
    private fun getUsers(): MutableMap<String, User> {
        val json = encryptedPrefs.getString(KEY_USERS, "{}") ?: "{}"
        val type = object : TypeToken<Map<String, User>>() {}.type
        return gson.fromJson(json, type) ?: mutableMapOf()
    }
    
    // Save all users
    private fun saveUsers(users: Map<String, User>) {
        val json = gson.toJson(users)
        encryptedPrefs.edit().putString(KEY_USERS, json).apply()
    }
    
    // Get all keys
    private fun getKeys(): MutableList<Key> {
        val json = encryptedPrefs.getString(KEY_KEYS, "[]") ?: "[]"
        val type = object : TypeToken<List<Key>>() {}.type
        return gson.fromJson(json, type) ?: mutableListOf()
    }
    
    // Save all keys
    private fun saveKeys(keys: List<Key>) {
        val json = gson.toJson(keys)
        encryptedPrefs.edit().putString(KEY_KEYS, json).apply()
    }
    
    // Hash password (simple hash for demo - use bcrypt in production)
    private fun hashPassword(password: String): String {
        return java.security.MessageDigest.getInstance("SHA-256")
            .digest(password.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    
    // Register new user
    fun registerUser(username: String, password: String): Boolean {
        if (password.length != 8) return false
        
        val users = getUsers()
        if (users.containsKey(username)) return false
        
        val user = User(username, hashPassword(password))
        users[username] = user
        saveUsers(users)
        
        return true
    }
    
    // Authenticate user
    fun authenticate(username: String, password: String): User? {
        val users = getUsers()
        val user = users[username] ?: return null
        
        return if (user.passwordHash == hashPassword(password)) user else null
    }
    
    // Update user profile
    fun updateUserProfile(oldUsername: String, newUsername: String, newPassword: String?): Boolean {
        val users = getUsers()
        val user = users[oldUsername] ?: return false
        
        users.remove(oldUsername)
        
        val passwordHash = if (newPassword != null && newPassword.isNotEmpty()) {
            if (newPassword.length != 8) return false
            hashPassword(newPassword)
        } else {
            user.passwordHash
        }
        
        val updatedUser = User(newUsername, passwordHash)
        users[newUsername] = updatedUser
        saveUsers(users)
        
        // Update keys associated with this user
        val keys = getKeys().map { 
            if (it.userId == oldUsername) it.copy(userId = newUsername) else it 
        }
        saveKeys(keys)
        
        return true
    }
    
    // Delete user
    fun deleteUser(username: String): Boolean {
        val users = getUsers()
        if (!users.containsKey(username)) return false
        
        users.remove(username)
        saveUsers(users)
        
        // Remove all keys associated with this user
        val keys = getKeys().filter { it.userId != username }
        saveKeys(keys)
        
        return true
    }
    
    // Create a new key for user
    fun createKey(userId: String, type: String, value: String): Key? {
        val keys = getKeys()
        
        // Check if key already exists for this user
        if (keys.any { it.userId == userId && it.value == value }) {
            return null
        }
        
        val key = Key(
            id = UUID.randomUUID().toString(),
            userId = userId,
            type = type,
            value = value
        )
        
        keys.add(key)
        saveKeys(keys)
        
        return key
    }
    
    // Get all keys for a user
    fun getUserKeys(userId: String): List<Key> {
        return getKeys().filter { it.userId == userId }
    }
    
    // Update a key
    fun updateKey(keyId: String, newValue: String): Boolean {
        val keys = getKeys().toMutableList()
        val index = keys.indexOfFirst { it.id == keyId }
        
        if (index == -1) return false
        
        keys[index] = keys[index].copy(value = newValue)
        saveKeys(keys)
        
        return true
    }
    
    // Delete a key
    fun deleteKey(keyId: String): Boolean {
        val keys = getKeys()
        val filteredKeys = keys.filter { it.id != keyId }
        
        if (filteredKeys.size == keys.size) return false
        
        saveKeys(filteredKeys)
        return true
    }
}
