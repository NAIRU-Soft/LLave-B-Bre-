package com.nairusoft.bbre.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordHasherTest {

    @Test
    fun createCredentials_generatesUniqueSaltAndHashForSamePassword() {
        val creds1 = PasswordHasher.createCredentials("ClaveSegura123")
        val creds2 = PasswordHasher.createCredentials("ClaveSegura123")

        assertNotEquals(creds1.saltBase64, creds2.saltBase64)
        assertNotEquals(creds1.hashBase64, creds2.hashBase64)
        assertEquals(PasswordHasher.ALGORITHM_PBKDF2_SHA256, creds1.algorithm)
        assertEquals(PasswordHasher.DEFAULT_ITERATIONS, creds1.iterations)
    }

    @Test
    fun verifyPassword_acceptsValidAndRejectsInvalid() {
        val creds = PasswordHasher.createCredentials("MiClave#2026")

        assertTrue(
            PasswordHasher.verifyPassword(
                password = "MiClave#2026",
                storedHash = creds.hashBase64,
                storedSalt = creds.saltBase64,
                algorithm = creds.algorithm,
                iterations = creds.iterations
            )
        )
        assertFalse(
            PasswordHasher.verifyPassword(
                password = "OtraClave#2026",
                storedHash = creds.hashBase64,
                storedSalt = creds.saltBase64,
                algorithm = creds.algorithm,
                iterations = creds.iterations
            )
        )
        assertFalse(
            PasswordHasher.needsMigration(creds.saltBase64, creds.algorithm, creds.iterations)
        )
    }

    @Test
    fun verifyPassword_supportsLegacySha256AndFlagsMigration() {
        val legacyHash = PasswordHasher.sha256Hex("clave1234")

        assertTrue(
            PasswordHasher.verifyPassword(
                password = "clave1234",
                storedHash = legacyHash,
                storedSalt = "",
                algorithm = "",
                iterations = 0
            )
        )
        assertFalse(
            PasswordHasher.verifyPassword(
                password = "wrong1234",
                storedHash = legacyHash,
                storedSalt = "",
                algorithm = "",
                iterations = 0
            )
        )
        assertTrue(PasswordHasher.needsMigration("", "", 0))
    }
}
