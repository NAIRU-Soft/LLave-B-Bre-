package com.nairusoft.bbre.security

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Arrays
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Pure-JVM password hashing and verification using PBKDF2WithHmacSHA256
 * with per-user random salt and constant-time comparison.
 */
object PasswordHasher {

    const val ALGORITHM_PBKDF2_SHA256 = "PBKDF2WithHmacSHA256"
    const val DEFAULT_ITERATIONS = 120_000
    const val SALT_BYTES = 16
    private const val KEY_LENGTH_BITS = 256

    data class HashedCredentials(
        val hashBase64: String,
        val saltBase64: String,
        val algorithm: String,
        val iterations: Int
    )

    private val secureRandom = SecureRandom()

    fun createCredentials(password: String): HashedCredentials {
        val salt = ByteArray(SALT_BYTES)
        secureRandom.nextBytes(salt)

        val chars = password.toCharArray()
        val derived = try {
            derivePbkdf2(chars, salt, DEFAULT_ITERATIONS)
        } finally {
            Arrays.fill(chars, '\u0000')
        }

        val encoder = Base64.getEncoder()
        return HashedCredentials(
            hashBase64 = encoder.encodeToString(derived),
            saltBase64 = encoder.encodeToString(salt),
            algorithm = ALGORITHM_PBKDF2_SHA256,
            iterations = DEFAULT_ITERATIONS
        )
    }

    fun verifyPassword(
        password: String,
        storedHash: String,
        storedSalt: String,
        algorithm: String,
        iterations: Int
    ): Boolean {
        if (storedHash.isEmpty()) return false

        if (storedSalt.isNotEmpty() && algorithm == ALGORITHM_PBKDF2_SHA256 && iterations > 0) {
            return try {
                val decoder = Base64.getDecoder()
                val saltBytes = decoder.decode(storedSalt)
                val expectedBytes = decoder.decode(storedHash)
                val chars = password.toCharArray()
                val actualBytes = try {
                    derivePbkdf2(chars, saltBytes, iterations)
                } finally {
                    Arrays.fill(chars, '\u0000')
                }
                MessageDigest.isEqual(actualBytes, expectedBytes)
            } catch (_: Exception) {
                false
            }
        }

        // Legacy unsalted SHA-256 hex compatibility for migration on first successful login
        return try {
            val legacyDigest = sha256Hex(password).toByteArray(StandardCharsets.UTF_8)
            val expectedBytes = storedHash.lowercase().toByteArray(StandardCharsets.UTF_8)
            MessageDigest.isEqual(legacyDigest, expectedBytes)
        } catch (_: Exception) {
            false
        }
    }

    fun needsMigration(
        storedSalt: String,
        algorithm: String,
        iterations: Int
    ): Boolean {
        return storedSalt.isEmpty() ||
            algorithm != ALGORITHM_PBKDF2_SHA256 ||
            iterations < DEFAULT_ITERATIONS
    }

    fun sha256Hex(data: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun derivePbkdf2(passwordChars: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(passwordChars, salt, iterations, KEY_LENGTH_BITS)
        return try {
            val factory = SecretKeyFactory.getInstance(ALGORITHM_PBKDF2_SHA256)
            factory.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
