package com.nairusoft.bbre.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
        const val BIOMETRIC_KEY_ALIAS = "bbre_biometric_key"
        private const val KEY_BIOMETRIC_TOKEN_ENC = "biometric_token_enc"
        private const val KEY_BIOMETRIC_TOKEN_IV = "biometric_token_iv"
        private const val KEY_BIOMETRIC_NONCE_HASH = "biometric_nonce_hash"
        private const val KEY_BIOMETRIC_OWNER = "biometric_owner"
        
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

    sealed class BiometricCipherResult {
        data class Ready(val cipher: Cipher) : BiometricCipherResult()
        object KeyInvalidated : BiometricCipherResult()
        object Unavailable : BiometricCipherResult()
    }

    /**
     * Check if device supports strong (Class 3) biometric authentication compatible with CryptoObject.
     */
    fun isBiometricAvailable(): Boolean {
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Returns true when device only offers Class 2 (weak) biometrics, such as basic face unlock.
     */
    fun isWeakBiometricOnly(): Boolean {
        if (isBiometricAvailable()) return false
        val biometricManager = BiometricManager.from(context)
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    /**
     * Get or generate a secure key in Android Keystore with strong biometric requirement.
     * Does NOT overwrite an existing key unless forceRecreate is true.
     */
    fun generateBiometricKey(alias: String, forceRecreate: Boolean = false): SecretKey? {
        return try {
            if (!forceRecreate && keyStore.containsAlias(alias)) {
                return getSecretKey(alias)
            }
            if (forceRecreate && keyStore.containsAlias(alias)) {
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

    fun hasBiometricEnrollmentFor(username: String): Boolean {
        if (username.isEmpty() || !isBiometricAvailable()) return false
        val owner = getSecureData(KEY_BIOMETRIC_OWNER)
        val enc = getSecureData(KEY_BIOMETRIC_TOKEN_ENC)
        val iv = getSecureData(KEY_BIOMETRIC_TOKEN_IV)
        val nonceHash = getSecureData(KEY_BIOMETRIC_NONCE_HASH)
        return try {
            owner.equals(username, ignoreCase = true) &&
                enc.isNotEmpty() &&
                iv.isNotEmpty() &&
                nonceHash.isNotEmpty() &&
                keyStore.containsAlias(BIOMETRIC_KEY_ALIAS)
        } catch (_: Exception) {
            false
        }
    }

    fun createBiometricEncryptCipher(alias: String = BIOMETRIC_KEY_ALIAS): BiometricCipherResult {
        return try {
            val key = generateBiometricKey(alias, forceRecreate = false)
                ?: return BiometricCipherResult.Unavailable
            val cipher = Cipher.getInstance(TRANSFORMATION_AES)
            try {
                cipher.init(Cipher.ENCRYPT_MODE, key)
                BiometricCipherResult.Ready(cipher)
            } catch (_: KeyPermanentlyInvalidatedException) {
                val freshKey = generateBiometricKey(alias, forceRecreate = true)
                    ?: return BiometricCipherResult.Unavailable
                val freshCipher = Cipher.getInstance(TRANSFORMATION_AES)
                freshCipher.init(Cipher.ENCRYPT_MODE, freshKey)
                BiometricCipherResult.Ready(freshCipher)
            }
        } catch (_: Exception) {
            BiometricCipherResult.Unavailable
        }
    }

    fun createBiometricDecryptCipher(alias: String = BIOMETRIC_KEY_ALIAS): BiometricCipherResult {
        val ivBase64 = getSecureData(KEY_BIOMETRIC_TOKEN_IV)
        if (ivBase64.isEmpty()) return BiometricCipherResult.Unavailable
        return try {
            val key = getSecretKey(alias) ?: return BiometricCipherResult.KeyInvalidated
            val ivBytes = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION_AES)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_LENGTH, ivBytes))
            BiometricCipherResult.Ready(cipher)
        } catch (_: KeyPermanentlyInvalidatedException) {
            BiometricCipherResult.KeyInvalidated
        } catch (_: Exception) {
            BiometricCipherResult.Unavailable
        }
    }

    fun saveBiometricEnrollment(username: String, authenticatedCipher: Cipher): Boolean {
        return try {
            val nonceBytes = ByteArray(32)
            SecureRandom().nextBytes(nonceBytes)
            val nonce = android.util.Base64.encodeToString(nonceBytes, android.util.Base64.NO_WRAP)
            val payload = "$username|$nonce"
            val encryptedBytes = authenticatedCipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))

            storeSecureData(
                KEY_BIOMETRIC_TOKEN_ENC,
                android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP)
            )
            storeSecureData(
                KEY_BIOMETRIC_TOKEN_IV,
                android.util.Base64.encodeToString(authenticatedCipher.iv, android.util.Base64.NO_WRAP)
            )
            storeSecureData(KEY_BIOMETRIC_NONCE_HASH, hashData(nonce))
            storeSecureData(KEY_BIOMETRIC_OWNER, username)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun verifyBiometricDecryption(authenticatedCipher: Cipher): String? {
        return try {
            val encBase64 = getSecureData(KEY_BIOMETRIC_TOKEN_ENC)
            val storedNonceHash = getSecureData(KEY_BIOMETRIC_NONCE_HASH)
            val storedOwner = getSecureData(KEY_BIOMETRIC_OWNER)
            if (encBase64.isEmpty() || storedNonceHash.isEmpty() || storedOwner.isEmpty()) {
                return null
            }

            val encBytes = android.util.Base64.decode(encBase64, android.util.Base64.NO_WRAP)
            val plainText = String(authenticatedCipher.doFinal(encBytes), StandardCharsets.UTF_8)
            val separatorIndex = plainText.indexOf('|')
            if (separatorIndex <= 0) return null

            val decryptedUser = plainText.substring(0, separatorIndex)
            val decryptedNonce = plainText.substring(separatorIndex + 1)
            val candidateHash = hashData(decryptedNonce).toByteArray(StandardCharsets.UTF_8)
            val expectedHash = storedNonceHash.toByteArray(StandardCharsets.UTF_8)

            if (decryptedUser.equals(storedOwner, ignoreCase = true) &&
                MessageDigest.isEqual(candidateHash, expectedHash)
            ) {
                decryptedUser
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun clearBiometricEnrollment(alias: String = BIOMETRIC_KEY_ALIAS) {
        removeSecureData(KEY_BIOMETRIC_TOKEN_ENC)
        removeSecureData(KEY_BIOMETRIC_TOKEN_IV)
        removeSecureData(KEY_BIOMETRIC_NONCE_HASH)
        removeSecureData(KEY_BIOMETRIC_OWNER)
        try {
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
        } catch (_: Exception) {
            // Best-effort cleanup
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
        return encryptResult(data, alias).getOrNull()
    }

    /**
     * Encrypt data using AES-GCM returning a Result
     */
    fun encryptResult(data: String, alias: String): Result<ByteArray> {
        return try {
            val key = getSecretKey(alias)
                ?: return Result.failure(IllegalStateException("Clave de cifrado no disponible: $alias"))
            val cipher = Cipher.getInstance(TRANSFORMATION_AES)
            cipher.init(Cipher.ENCRYPT_MODE, key)

            val iv = cipher.iv
            val encryptedData = cipher.doFinal(data.toByteArray(StandardCharsets.UTF_8))

            // Combine IV and encrypted data
            Result.success(iv + encryptedData)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Encrypt data and return Base64 string
     */
    fun encryptToBase64(data: String, alias: String): String? {
        return encryptToBase64Result(data, alias).getOrNull()
    }

    /**
     * Encrypt data and return Base64 string as Result
     */
    fun encryptToBase64Result(data: String, alias: String): Result<String> {
        return encryptResult(data, alias).map { encrypted ->
            android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
        }
    }

    /**
     * Decrypt data from Base64 string
     */
    fun decryptFromBase64(base64Data: String, alias: String): String? {
        return decryptFromBase64Result(base64Data, alias).getOrNull()
    }

    /**
     * Decrypt data from Base64 string returning a Result
     */
    fun decryptFromBase64Result(base64Data: String, alias: String): Result<String> {
        return try {
            val encryptedData = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
            decryptResult(encryptedData, alias)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Decrypt data using AES-GCM
     */
    fun decrypt(encryptedData: ByteArray, alias: String): String? {
        return decryptResult(encryptedData, alias).getOrNull()
    }

    /**
     * Decrypt data using AES-GCM returning a Result
     */
    fun decryptResult(encryptedData: ByteArray, alias: String): Result<String> {
        return try {
            if (encryptedData.size <= GCM_IV_LENGTH) {
                return Result.failure(IllegalArgumentException("Datos cifrados inválidos o truncados"))
            }
            val key = getSecretKey(alias)
                ?: return Result.failure(IllegalStateException("Clave de descifrado no disponible: $alias"))

            // Extract IV and encrypted data
            val iv = encryptedData.copyOfRange(0, GCM_IV_LENGTH)
            val actualData = encryptedData.copyOfRange(GCM_IV_LENGTH, encryptedData.size)

            val cipher = Cipher.getInstance(TRANSFORMATION_AES)
            val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)

            val decryptedData = cipher.doFinal(actualData)
            Result.success(String(decryptedData, StandardCharsets.UTF_8))
        } catch (e: Exception) {
            Result.failure(e)
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
     * Create salted PBKDF2 user credentials.
     */
    fun createUserCredentials(username: String, password: String): User {
        val creds = PasswordHasher.createCredentials(password)
        return User(
            username = username,
            passwordHash = creds.hashBase64,
            salt = creds.saltBase64,
            kdfAlgorithm = creds.algorithm,
            kdfIterations = creds.iterations
        )
    }

    /**
     * Verify user password (supports PBKDF2 and legacy unsalted SHA-256 for migration).
     */
    fun verifyUserPassword(user: User, password: String): Boolean {
        return PasswordHasher.verifyPassword(
            password = password,
            storedHash = user.passwordHash,
            storedSalt = user.salt,
            algorithm = user.kdfAlgorithm,
            iterations = user.kdfIterations
        )
    }

    /**
     * Returns true if the stored user record uses an outdated/unsalted hash format.
     */
    fun needsPasswordMigration(user: User): Boolean {
        return PasswordHasher.needsMigration(
            storedSalt = user.salt,
            algorithm = user.kdfAlgorithm,
            iterations = user.kdfIterations
        )
    }

    /**
     * Hash data using SHA-256 (non-password utility)
     */
    fun hashData(data: String): String {
        return try {
            PasswordHasher.sha256Hex(data)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Verify if PIN is valid (constant-time comparison)
     */
    fun verifyPin(storedHash: String, inputPin: String): Boolean {
        val candidate = hashData(inputPin).toByteArray(StandardCharsets.UTF_8)
        val stored = storedHash.lowercase().toByteArray(StandardCharsets.UTF_8)
        return candidate.isNotEmpty() && MessageDigest.isEqual(candidate, stored)
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
