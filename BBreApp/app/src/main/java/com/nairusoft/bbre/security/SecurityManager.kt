package com.nairusoft.bbre.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import kotlin.concurrent.thread

/**
 * SecurityManager - Central security component for BBre
 * Handles encryption, decryption, biometric authentication, and secure storage
 */
class SecurityManager(private val context: Context) {

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION_AES = "AES/GCM/NoPadding"
        private const val GCM_IV_LENGTH = 12
        private const val GCM_TAG_LENGTH = 128
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

    private val keyStore: KeyStore
    private val encryptedPrefs: EncryptedSharedPreferences
    private val masterKey: MasterKey

    init {
        keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply {
            load(null)
        }
        
        masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        
        encryptedPrefs = EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        ) as EncryptedSharedPreferences
    }

    /**
     * Check if device supports biometric authentication
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK
        
        return biometricManager.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Generate a secure key in Android Keystore with biometric requirement
     */
    fun generateBiometricKey(alias: String): SecretKey? {
        return try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
                .setInvalidatedByBiometricEnrollment(true)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generate a standard secure key in Android Keystore (no biometric requirement)
     */
    fun generateStandardKey(alias: String): SecretKey? {
        return try {
            if (keyStore.containsAlias(alias)) {
                return getSecretKey(alias)
            }

            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE
            )

            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()

            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Get secret key from keystore
     */
    fun getSecretKey(alias: String): SecretKey? {
        return try {
            keyStore.getKey(alias, null) as? SecretKey
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Encrypt data using AES-GCM
     */
    fun encrypt(data: String, alias: String): ByteArray? {
        return try {
            val key = getSecretKey(alias) ?: return null
            val cipher = Cipher.getInstance(TRANSFORMATION_AES)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            
            val iv = cipher.iv
            val encryptedData = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            
            // Combine IV and encrypted data
            iv + encryptedData
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Encrypt data and return Base64 string
     */
    fun encryptToBase64(data: String, alias: String): String? {
        val encrypted = encrypt(data, alias) ?: return null
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.DEFAULT)
    }

    /**
     * Decrypt data from Base64 string
     */
    fun decryptFromBase64(base64Data: String, alias: String): String? {
        return try {
            val encryptedData = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            decrypt(encryptedData, alias)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Decrypt data using AES-GCM
     */
    fun decrypt(encryptedData: ByteArray, alias: String): String? {
        return try {
            val key = getSecretKey(alias) ?: return null
            
            // Extract IV and encrypted data
            val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
            val actualData = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)
            
            val cipher = Cipher.getInstance(TRANSFORMATION_AES)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            
            val decryptedData = cipher.doFinal(actualData)
            String(decryptedData, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Store sensitive data securely
     */
    fun storeSecureData(key: String, value: String) {
        encryptedPrefs.edit().putString(key, value).apply()
    }

    /**
     * Retrieve sensitive data securely
     */
    fun getSecureData(key: String, defaultValue: String = ""): String {
        return encryptedPrefs.getString(key, defaultValue) ?: defaultValue
    }

    /**
     * Remove sensitive data
     */
    fun removeSecureData(key: String) {
        encryptedPrefs.edit().remove(key).apply()
    }

    /**
     * Clear all secure data
     */
    fun clearAllSecureData() {
        encryptedPrefs.edit().clear().apply()
    }

    /**
     * Hash data using SHA-256
     */
    fun hashData(data: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            val digest = md.digest(data.toByteArray(StandardCharsets.UTF_8))
            digest.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Verify if PIN is valid (hashed comparison)
     */
    fun verifyPin(storedHash: String, inputPin: String): Boolean {
        return storedHash == hashData(inputPin)
    }

    /**
     * Check if app is running in debug mode (security risk)
     */
    fun isDebugMode(): Boolean {
        return context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
    }

    /**
     * Perform security check before sensitive operations
     */
    fun performSecurityCheck(): SecurityStatus {
        val issues = mutableListOf<String>()
        
        if (isDebugMode()) {
            issues.add("App running in debug mode")
        }
        
        if (!isBiometricAvailable()) {
            issues.add("Biometric authentication not available")
        }
        
        return if (issues.isEmpty()) {
            SecurityStatus.SECURE
        } else {
            SecurityStatus.WARNING(issues)
        }
    }

    /**
     * Validate transfer amount against BBre limits
     */
    fun validateTransferAmount(amount: Long): TransferValidationResult {
        val maxAmount = 12_110_000L // BBre limit in COP
        
        return when {
            amount <= 0 -> TransferValidationResult.INVALID_AMOUNT
            amount > maxAmount -> TransferValidationResult.EXCEEDS_LIMIT(maxAmount)
            else -> TransferValidationResult.VALID
        }
    }

    /**
     * Securely wipe sensitive data from memory
     */
    fun secureWipe(sensitiveData: CharArray) {
        thread {
            try {
                for (i in sensitiveData.indices) {
                    sensitiveData[i] = '\u0000'
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

/**
 * Security status enum
 */
sealed class SecurityStatus {
    object SECURE : SecurityStatus()
    data class WARNING(val issues: List<String>) : SecurityStatus()
}

/**
 * Transfer validation result
 */
sealed class TransferValidationResult {
    object VALID : TransferValidationResult()
    object INVALID_AMOUNT : TransferValidationResult()
    data class EXCEEDS_LIMIT(val maxAmount: Long) : TransferValidationResult()
}
