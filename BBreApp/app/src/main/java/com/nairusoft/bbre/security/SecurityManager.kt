package com.nairusoft.bbre.security

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec

/**
 * SecurityManager - Handles biometric authentication and secure data storage
 */
class SecurityManager private constructor(private val context: Context) {
    
    companion object {
        private const val PREF_NAME = "bbre_secure_prefs"
        
        @Volatile
        private var instance: SecurityManager? = null
        
        fun getInstance(context: Context): SecurityManager {
            return instance ?: synchronized(this) {
                instance ?: SecurityManager(context.applicationContext).also {
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
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }
    }
    
    /**
     * Check if biometric authentication is available
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return when (biometricManager.canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.BIOMETRIC_WEAK
        )) {
            BiometricManager.BIOMETRIC_SUCCESS -> true
            else -> false
        }
    }
    
    /**
     * Generate a biometric key for authentication
     */
    suspend fun generateBiometricKey(alias: String) {
        try {
            if (!keyStore.containsAlias(alias)) {
                val keyGenerator = KeyGenerator.getInstance(
                    "AES", "AndroidKeyStore"
                )
                keyGenerator.init(
                    android.security.keystore.KeyGenParameterSpec.Builder(
                        alias,
                        android.security.keystore.KeyProperties.PURPOSE_ENCRYPT or
                        android.security.keystore.KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(android.security.keystore.KeyProperties.BLOCK_MODE_CBC)
                        .setEncryptionPaddings(android.security.keystore.KeyProperties.ENCRYPTION_PADDING_PKCS7)
                        .setUserAuthenticationRequired(true)
                        .build()
                )
                keyGenerator.generateKey()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Get cipher for biometric authentication
     */
    fun getBiometricCipher(alias: String): Cipher? {
        return try {
            val key = keyStore.getKey(alias, null) as SecretKey
            Cipher.getInstance("AES/CBC/PKCS7Padding").apply {
                init(Cipher.ENCRYPT_MODE, key)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Store secure data in encrypted preferences
     */
    fun storeSecureData(key: String, value: String) {
        encryptedPrefs.edit().putString(key, value).apply()
    }
    
    /**
     * Retrieve secure data from encrypted preferences
     */
    fun getSecureData(key: String, defaultValue: String): String {
        return encryptedPrefs.getString(key, defaultValue) ?: defaultValue
    }
    
    /**
     * Clear secure data
     */
    fun clearSecureData(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }
}
