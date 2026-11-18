package com.nairusoft.bbre.security

import com.nairusoft.bbre.model.BackupPayload
import com.nairusoft.bbre.model.EncryptedBackupEnvelope
import com.nairusoft.bbre.model.User
import com.nairusoft.bbre.model.UserKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BackupCryptoManagerTest {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun samplePayload(): BackupPayload {
        val creds = PasswordHasher.createCredentials("MiClaveSecreta1")
        val user = User(
            username = "oscar.pinzon",
            passwordHash = creds.hashBase64,
            salt = creds.saltBase64,
            kdfAlgorithm = creds.algorithm,
            kdfIterations = creds.iterations
        )
        val keys = listOf(
            UserKey(
                id = "k-1",
                type = "Nequi",
                value = "3009998877",
                username = "oscar.pinzon",
                createdBy = "oscar.pinzon",
                creationDate = "2026-11-17 10:00:00",
                modificationDate = "2026-11-17 10:00:00",
                version = "0.0.1",
                description = "Cuenta ahorro"
            )
        )
        return BackupPayload(
            schemaVersion = 1,
            appVersion = "0.0.1",
            exportedAt = "2026-11-17 10:05:00",
            user = user,
            keys = keys
        )
    }

    @Test
    fun encryptAndDecrypt_roundTripPreservesUserAndKeysWithoutPlaintextLeak() {
        val payload = samplePayload()
        val encrypted = BackupCryptoManager.encryptToJson(payload, "RespaldoSeguro1").getOrThrow()

        // Envelope contains AES-256-GCM parameters without leaking user/keys plaintext
        val envelope = json.decodeFromString<EncryptedBackupEnvelope>(encrypted)
        assertEquals(BackupCryptoManager.FORMAT_ID, envelope.format)
        assertEquals(BackupCryptoManager.CIPHER_LABEL, envelope.cipher)
        assertEquals(BackupCryptoManager.KDF_ALGORITHM, envelope.kdf)
        assertEquals(BackupCryptoManager.PBKDF2_ITERATIONS, envelope.kdfIterations)

        assertFalse(encrypted.contains("oscar.pinzon"))
        assertFalse(encrypted.contains("3009998877"))
        assertFalse(encrypted.contains("Cuenta ahorro"))
        assertFalse(encrypted.contains("Nequi"))

        val decrypted = BackupCryptoManager.decryptFromJson(encrypted, "RespaldoSeguro1").getOrThrow()
        assertEquals(payload, decrypted)
    }

    @Test
    fun decrypt_rejectsWrongPassphraseTamperedCiphertextAndInvalidFormat() {
        val encrypted = BackupCryptoManager.encryptToJson(samplePayload(), "ClaveCorrecta1").getOrThrow()

        val wrongPassword = BackupCryptoManager.decryptFromJson(encrypted, "OtraClave99")
        assertTrue(wrongPassword.isFailure)
        assertTrue(wrongPassword.exceptionOrNull() is BackupCryptoManager.BackupCryptoException.WrongPassphraseOrCorrupted)

        // Flip a byte inside ciphertextBase64 -> AEAD tag check fails
        val envelope = json.decodeFromString<EncryptedBackupEnvelope>(encrypted)
        val rawCipher = Base64.getDecoder().decode(envelope.ciphertextBase64)
        rawCipher[0] = (rawCipher[0].toInt() xor 0xFF).toByte()
        val tampered = json.encodeToString(
            envelope.copy(ciphertextBase64 = Base64.getEncoder().encodeToString(rawCipher))
        )
        val tamperedResult = BackupCryptoManager.decryptFromJson(tampered, "ClaveCorrecta1")
        assertTrue(tamperedResult.isFailure)
        assertTrue(tamperedResult.exceptionOrNull() is BackupCryptoManager.BackupCryptoException.WrongPassphraseOrCorrupted)

        // Downgrade / manipulate AAD iterations header -> rejected
        val downgraded = json.encodeToString(envelope.copy(kdfIterations = 1_000))
        val downgradedResult = BackupCryptoManager.decryptFromJson(downgraded, "ClaveCorrecta1")
        assertTrue(downgradedResult.isFailure)

        val invalidJson = BackupCryptoManager.decryptFromJson("{not-valid", "ClaveCorrecta1")
        assertTrue(invalidJson.isFailure)
        assertTrue(invalidJson.exceptionOrNull() is BackupCryptoManager.BackupCryptoException.InvalidFormat)
    }
}
