package com.nairusoft.bbre.security

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.Base64

class StorageManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCryptoProvider(var failDecrypt: Boolean = false) : StorageManager.CryptoProvider {
        override fun ensureKey(alias: String): Boolean = true

        override fun encrypt(plainText: String, alias: String): Result<String> {
            return Result.success(Base64.getEncoder().encodeToString(plainText.toByteArray(StandardCharsets.UTF_8)))
        }

        override fun decrypt(cipherText: String, alias: String): Result<String> {
            if (failDecrypt) {
                return Result.failure(IllegalStateException("Simulated decryption failure"))
            }
            return try {
                val decoded = Base64.getDecoder().decode(cipherText)
                Result.success(String(decoded, StandardCharsets.UTF_8))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    @Test
    fun jsonSerialization_roundTripsLegacyAndPbkdf2UsersAndKeys() {
        val json = Json { ignoreUnknownKeys = true }
        val legacyJson = """[{"username":"legacy","passwordHash":"abc123"}]"""
        val decodedUsers = json.decodeFromString<List<User>>(legacyJson)

        assertEquals(1, decodedUsers.size)
        assertEquals("legacy", decodedUsers[0].username)
        assertEquals("", decodedUsers[0].salt)
        assertEquals(0, decodedUsers[0].kdfIterations)

        val key = UserKey(
            id = "k1",
            type = "Bancolombia",
            value = "3001112233",
            username = "ana",
            createdBy = "ana",
            creationDate = "2026-11-16 12:00:00",
            modificationDate = "2026-11-16 12:00:00",
            version = "0.0.1",
            description = "Cuenta principal"
        )
        val encoded = json.encodeToString(listOf(key))
        val decodedKeys = json.decodeFromString<List<UserKey>>(encoded)
        assertEquals(listOf(key), decodedKeys)
    }

    @Test
    fun corruptedFile_neverOverwrittenOnAddKeyOrAddUser() = runBlocking {
        val dir = tempFolder.newFolder("bbre_corrupt")
        val crypto = FakeCryptoProvider()
        val storage = StorageManager(dir, crypto)

        val creds = PasswordHasher.createCredentials("ClaveSegura1")
        val user = User("ana", creds.hashBase64, creds.saltBase64, creds.algorithm, creds.iterations)
        assertEquals(StorageOpResult.Success, storage.addUser(user))

        val originalKey = UserKey(
            id = "id-1",
            type = "Nequi",
            value = "3001234567",
            username = "ana",
            createdBy = "ana",
            description = "Mi Nequi"
        )
        assertEquals(StorageOpResult.Success, storage.addKey(originalKey))

        val keysFile = File(dir, "llaves.json")
        val originalCiphertext = keysFile.readText()

        // Corrupt ciphertext and verify addKey refuses to overwrite
        keysFile.writeText("CORRUPT_DATA_NOT_BASE64")
        val secondKey = originalKey.copy(id = "id-2", value = "3009998877")
        val opResult = storage.addKey(secondKey)
        assertTrue(opResult is StorageOpResult.Error)
        assertEquals("CORRUPT_DATA_NOT_BASE64", keysFile.readText())

        // Restore valid ciphertext and verify data remained intact
        keysFile.writeText(originalCiphertext)
        val loaded = storage.getKeysForUserResult("ana") as StorageLoadResult.Success
        assertEquals(1, loaded.data.size)
        assertEquals("3001234567", loaded.data[0].value)
    }

    @Test
    fun keyUniqueness_normalizesSpacesAndCasePerUserAndRenamesPreserveCreatedBy() = runBlocking {
        val dir = tempFolder.newFolder("bbre_crud")
        val storage = StorageManager(dir, FakeCryptoProvider())

        assertEquals(StorageOpResult.Success, storage.addUser(User("ana", "hash1")))
        assertEquals(StorageOpResult.Success, storage.addUser(User("luis", "hash2")))

        val k1 = UserKey(id = "1", type = "  Banco   de Bogotá ", value = " 3001234567 ", username = "ana", createdBy = "ana")
        assertEquals(StorageOpResult.Success, storage.addKey(k1))

        // Duplicate normalized key for same user -> rejected
        val dup = UserKey(id = "2", type = "banco de bogotá", value = "3001234567", username = "ANA", createdBy = "ana")
        assertEquals(StorageOpResult.Duplicate, storage.addKey(dup))

        // Same key for different user -> allowed (D-05)
        val otherUserKey = UserKey(id = "3", type = "Banco de Bogotá", value = "3001234567", username = "luis", createdBy = "luis")
        assertEquals(StorageOpResult.Success, storage.addKey(otherUserKey))

        // Rename user ana -> ana_nueva preserves historical createdBy = "ana" (D-04)
        assertEquals(StorageOpResult.Success, storage.updateUser("ana", User("ana_nueva", "hash1")))
        val renamedKeys = (storage.getKeysForUserResult("ana_nueva") as StorageLoadResult.Success).data
        assertEquals(1, renamedKeys.size)
        assertEquals("ana_nueva", renamedKeys[0].username)
        assertEquals("ana", renamedKeys[0].createdBy)

        // Delete user deletes only that user's keys
        assertEquals(StorageOpResult.Success, storage.deleteUser("ana_nueva"))
        val luisKeys = (storage.getKeysForUserResult("luis") as StorageLoadResult.Success).data
        assertEquals(1, luisKeys.size)
    }

    @Test
    fun concurrentWrites_areSerializedByMutexWithoutLosingKeys() = runBlocking {
        val dir = tempFolder.newFolder("bbre_concurrency")
        val storage = StorageManager(dir, FakeCryptoProvider())

        val jobs = (1..20).map { i ->
            async {
                storage.addKey(
                    UserKey(
                        id = "key-$i",
                        type = "Nequi",
                        value = "30000000$i",
                        username = "ana",
                        createdBy = "ana",
                        description = "Key $i"
                    )
                )
            }
        }
        val results = jobs.awaitAll()
        assertTrue(results.all { it is StorageOpResult.Success })

        val loaded = storage.getKeysForUserResult("ana") as StorageLoadResult.Success
        assertEquals(20, loaded.data.size)
    }
}
