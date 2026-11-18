package com.nairusoft.bbre.security

import com.nairusoft.bbre.model.BackupPayload
import com.nairusoft.bbre.model.EncryptedBackupEnvelope
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pure-JVM AES-256-GCM + PBKDF2WithHmacSHA256 engine for encrypting and decrypting
 * portable application backups containing user credentials and stored keys.
 */
object BackupCryptoManager {

    const val FORMAT_ID = "BBRE-BACKUP-V1"
    const val CIPHER_LABEL = "AES-256-GCM"
    const val KDF_ALGORITHM = PasswordHasher.ALGORITHM_PBKDF2_SHA256
    const val PBKDF2_ITERATIONS = 120_000
    const val MIN_ITERATIONS = 100_000
    const val MAX_ITERATIONS = 1_000_000
    const val SALT_BYTES = 16
    const val GCM_IV_BYTES = 12
    const val GCM_TAG_BITS = 128
    const val AES_KEY_BITS = 256
    const val AES_KEY_BYTES = 32
    const val MAX_BACKUP_BYTES = 5 * 1024 * 1024 // 5 MiB guard against malformed/oversized inputs

    sealed class BackupCryptoException(message: String, cause: Throwable? = null) : Exception(message, cause) {
        class InvalidPassphrase(message: String) : BackupCryptoException(message)
        class WrongPassphraseOrCorrupted(message: String, cause: Throwable? = null) : BackupCryptoException(message, cause)
        class InvalidFormat(message: String, cause: Throwable? = null) : BackupCryptoException(message, cause)
    }

    private val secureRandom = SecureRandom()
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    /**
     * Encrypts [payload] using a 256-bit AES-GCM key derived from [passphraseChars] via PBKDF2WithHmacSHA256.
     * Clears temporary key material from memory before returning.
     */
    fun encryptToJson(payload: BackupPayload, passphraseChars: CharArray): Result<String> {
        if (passphraseChars.isEmpty()) {
            return Result.failure(BackupCryptoException.InvalidPassphrase("Contraseña de respaldo vacía"))
        }

        val salt = ByteArray(SALT_BYTES)
        val iv = ByteArray(GCM_IV_BYTES)
        secureRandom.nextBytes(salt)
        secureRandom.nextBytes(iv)

        var derivedKey: ByteArray? = null
        return try {
            derivedKey = deriveAes256Key(passphraseChars, salt, PBKDF2_ITERATIONS)
            val keySpec = SecretKeySpec(derivedKey, "AES")
            Arrays.fill(derivedKey, 0.toByte())
            derivedKey = null

            val aadBytes = buildAad(FORMAT_ID, CIPHER_LABEL, KDF_ALGORITHM, PBKDF2_ITERATIONS)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadBytes)

            val plainJsonBytes = json.encodeToString(payload).toByteArray(StandardCharsets.UTF_8)
            val encryptedBytes = try {
                cipher.doFinal(plainJsonBytes)
            } finally {
                Arrays.fill(plainJsonBytes, 0.toByte())
            }

            val encoder = Base64.getEncoder()
            val envelope = EncryptedBackupEnvelope(
                format = FORMAT_ID,
                cipher = CIPHER_LABEL,
                kdf = KDF_ALGORITHM,
                kdfIterations = PBKDF2_ITERATIONS,
                saltBase64 = encoder.encodeToString(salt),
                ivBase64 = encoder.encodeToString(iv),
                ciphertextBase64 = encoder.encodeToString(encryptedBytes)
            )
            Result.success(json.encodeToString(envelope))
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            derivedKey?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Convenience wrapper accepting a String passphrase; clears its temporary copy internally.
     */
    fun encryptToJson(payload: BackupPayload, passphrase: String): Result<String> {
        val chars = passphrase.toCharArray()
        return try {
            encryptToJson(payload, chars)
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    /**
     * Validates, decrypts, and authenticates an encrypted backup envelope using AES-256-GCM.
     */
    fun decryptFromJson(envelopeJson: String, passphraseChars: CharArray): Result<BackupPayload> {
        if (passphraseChars.isEmpty()) {
            return Result.failure(BackupCryptoException.InvalidPassphrase("Contraseña de respaldo vacía"))
        }
        val rawBytesSize = envelopeJson.toByteArray(StandardCharsets.UTF_8).size
        if (envelopeJson.isBlank() || rawBytesSize > MAX_BACKUP_BYTES) {
            return Result.failure(BackupCryptoException.InvalidFormat("Archivo de respaldo vacío o supera el tamaño permitido"))
        }

        val envelope = try {
            json.decodeFromString<EncryptedBackupEnvelope>(envelopeJson)
        } catch (e: Exception) {
            return Result.failure(BackupCryptoException.InvalidFormat("Formato de respaldo inválido", e))
        }

        if (envelope.format != FORMAT_ID ||
            envelope.cipher != CIPHER_LABEL ||
            envelope.kdf != KDF_ALGORITHM ||
            envelope.kdfIterations !in MIN_ITERATIONS..MAX_ITERATIONS
        ) {
            return Result.failure(BackupCryptoException.InvalidFormat("Versión o algoritmo de cifrado no compatible"))
        }

        val decoder = Base64.getDecoder()
        val salt: ByteArray
        val iv: ByteArray
        val ciphertext: ByteArray
        try {
            salt = decoder.decode(envelope.saltBase64)
            iv = decoder.decode(envelope.ivBase64)
            ciphertext = decoder.decode(envelope.ciphertextBase64)
        } catch (e: Exception) {
            return Result.failure(BackupCryptoException.InvalidFormat("Codificación Base64 inválida", e))
        }

        if (salt.size < SALT_BYTES || iv.size != GCM_IV_BYTES || ciphertext.size <= GCM_TAG_BITS / 8) {
            return Result.failure(BackupCryptoException.InvalidFormat("Parámetros criptográficos inválidos o truncados"))
        }

        var derivedKey: ByteArray? = null
        return try {
            derivedKey = deriveAes256Key(passphraseChars, salt, envelope.kdfIterations)
            val keySpec = SecretKeySpec(derivedKey, "AES")
            Arrays.fill(derivedKey, 0.toByte())
            derivedKey = null

            val aadBytes = buildAad(envelope.format, envelope.cipher, envelope.kdf, envelope.kdfIterations)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.updateAAD(aadBytes)

            val decryptedBytes = cipher.doFinal(ciphertext)
            val payload = try {
                val payloadJson = String(decryptedBytes, StandardCharsets.UTF_8)
                json.decodeFromString<BackupPayload>(payloadJson)
            } finally {
                Arrays.fill(decryptedBytes, 0.toByte())
            }

            if (payload.schemaVersion != 1 || payload.user.username.isBlank()) {
                return Result.failure(BackupCryptoException.InvalidFormat("Contenido del respaldo inválido"))
            }
            Result.success(payload)
        } catch (e: AEADBadTagException) {
            Result.failure(
                BackupCryptoException.WrongPassphraseOrCorrupted(
                    "Contraseña incorrecta o archivo de respaldo alterado",
                    e
                )
            )
        } catch (e: Exception) {
            Result.failure(
                BackupCryptoException.InvalidFormat("No se pudo procesar el archivo de respaldo", e)
            )
        } finally {
            derivedKey?.let { Arrays.fill(it, 0.toByte()) }
        }
    }

    /**
     * Convenience wrapper accepting a String passphrase; clears its temporary copy internally.
     */
    fun decryptFromJson(envelopeJson: String, passphrase: String): Result<BackupPayload> {
        val chars = passphrase.toCharArray()
        return try {
            decryptFromJson(envelopeJson, chars)
        } finally {
            Arrays.fill(chars, '\u0000')
        }
    }

    private fun deriveAes256Key(passphraseChars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passphraseChars, salt, iterations, AES_KEY_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(KDF_ALGORITHM)
            val keyBytes = factory.generateSecret(spec).encoded
            require(keyBytes.size == AES_KEY_BYTES) { "Se esperaba clave AES-256 (32 bytes)" }
            keyBytes
        } finally {
            spec.clearPassword()
        }
    }

    private fun buildAad(format: String, cipher: String, kdf: String, iterations: Int): ByteArray {
        return "$format|$cipher|$kdf|$iterations".toByteArray(StandardCharsets.UTF_8)
    }
}
