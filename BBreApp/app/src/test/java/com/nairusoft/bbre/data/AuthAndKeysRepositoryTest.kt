package com.nairusoft.bbre.data

import com.nairusoft.bbre.model.AccountDeleteOutcome
import com.nairusoft.bbre.model.BiometricBannerMode
import com.nairusoft.bbre.model.ProfileUpdateOutcome
import com.nairusoft.bbre.model.ReauthOutcome
import com.nairusoft.bbre.model.StorageLoadResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.User
import com.nairusoft.bbre.security.PasswordHasher
import com.nairusoft.bbre.security.SessionManager
import com.nairusoft.bbre.security.StorageManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.util.Base64

class AuthAndKeysRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeCryptoProvider : StorageManager.CryptoProvider {
        override fun ensureKey(alias: String): Boolean = true

        override fun encrypt(plainText: String, alias: String): Result<String> =
            Result.success(Base64.getEncoder().encodeToString(plainText.toByteArray(StandardCharsets.UTF_8)))

        override fun decrypt(cipherText: String, alias: String): Result<String> =
            Result.success(String(Base64.getDecoder().decode(cipherText), StandardCharsets.UTF_8))
    }

    private class FakeSecureAuthGateway : SecureAuthGateway {
        var biometricAvailable: Boolean = true
        var weakBiometricOnly: Boolean = false
        var enrolledUser: String = ""
        var lastLoggedUser: String = ""
        var sessionUser: String = ""

        override fun createUserCredentials(username: String, password: String): User {
            val creds = PasswordHasher.createCredentials(password)
            return User(
                username = username,
                passwordHash = creds.hashBase64,
                salt = creds.saltBase64,
                kdfAlgorithm = creds.algorithm,
                kdfIterations = creds.iterations
            )
        }

        override fun verifyUserPassword(user: User, password: String): Boolean =
            PasswordHasher.verifyPassword(password, user.passwordHash, user.salt, user.kdfAlgorithm, user.kdfIterations)

        override fun needsPasswordMigration(user: User): Boolean =
            PasswordHasher.needsMigration(user.salt, user.kdfAlgorithm, user.kdfIterations)

        override fun isBiometricAvailable(): Boolean = biometricAvailable
        override fun isWeakBiometricOnly(): Boolean = weakBiometricOnly
        override fun hasBiometricEnrollmentFor(username: String): Boolean =
            biometricAvailable && enrolledUser.equals(username, ignoreCase = true)

        override fun clearBiometricEnrollment() {
            enrolledUser = ""
        }

        override fun getLastLoggedInUser(): String = lastLoggedUser
        override fun setLastLoggedInUser(username: String) {
            lastLoggedUser = username
        }

        override fun clearLastLoggedInUser() {
            lastLoggedUser = ""
        }

        override fun getSessionUsername(): String = sessionUser
        override fun setSessionUsername(username: String) {
            sessionUser = username
        }

        override fun clearSessionUsername() {
            sessionUser = ""
        }
    }

    private lateinit var storageManager: StorageManager
    private lateinit var authGateway: FakeSecureAuthGateway
    private lateinit var sessionManager: SessionManager
    private lateinit var authRepository: AuthRepository
    private lateinit var keysRepository: KeysRepository
    private var nowMs = 10_000L

    @Before
    fun setUp() {
        storageManager = StorageManager(tempFolder.newFolder("bbre_repo"), FakeCryptoProvider())
        authGateway = FakeSecureAuthGateway()
        nowMs = 10_000L
        sessionManager = SessionManager(elapsedTimeProvider = { nowMs })
        authRepository = AuthRepository(storageManager, authGateway, sessionManager)
        keysRepository = KeysRepository(storageManager)
    }

    @Test
    fun login_migratesLegacySha256UserAndStartsSession() = runBlocking {
        val legacyUser = User(
            username = "legacy",
            passwordHash = PasswordHasher.sha256Hex("Clave1234")
        )
        assertEquals(StorageOpResult.Success, storageManager.addUser(legacyUser))

        val outcome = authRepository.login("legacy", "Clave1234")
        assertTrue(outcome is AuthRepository.LoginOutcome.Success)
        assertEquals("legacy", authRepository.getValidatedActiveUsername())

        val reloaded = (storageManager.getUsersResult() as StorageLoadResult.Success).data.first()
        assertFalse(authGateway.needsPasswordMigration(reloaded))
        assertEquals(PasswordHasher.ALGORITHM_PBKDF2_SHA256, reloaded.kdfAlgorithm)
    }

    @Test
    fun updateProfile_renamesUserClearsBiometricAndPreservesCreatedBy() = runBlocking {
        authRepository.register("alice", "Clave1234")
        authGateway.enrolledUser = "alice"
        authRepository.login("alice", "Clave1234")
        keysRepository.addKey("Nequi", "3001112233", "Personal", "alice", "2026-01-01 10:00:00", id = "k1")

        val renameResult = authRepository.updateProfile("alice", "alice2", "")
        assertEquals(ProfileUpdateOutcome.Updated("alice2"), renameResult)
        assertEquals("", authGateway.enrolledUser)
        assertEquals("alice2", authRepository.getValidatedActiveUsername())

        keysRepository.loadKeys("alice2")
        assertEquals(1, keysRepository.keys.value.size)
        assertEquals("alice", keysRepository.keys.value.first().createdBy)
    }

    @Test
    fun deleteAccount_removesUserKeysBiometricsAndSession() = runBlocking {
        authRepository.register("bob", "Clave1234")
        authGateway.enrolledUser = "bob"
        authRepository.login("bob", "Clave1234")
        keysRepository.addKey("Bancolombia", "999888777", "Cuenta", "bob", "2026-01-01 10:00:00", id = "k2")

        assertEquals(ReauthOutcome.Verified, authRepository.verifyCurrentPassword("bob", "Clave1234"))
        assertEquals(ReauthOutcome.WrongPassword, authRepository.verifyCurrentPassword("bob", "Incorrecta1"))

        assertEquals(AccountDeleteOutcome.Success, authRepository.deleteAccount("bob"))
        assertNull(authRepository.getValidatedActiveUsername())
        assertEquals("", authGateway.lastLoggedUser)
        assertEquals("", authGateway.enrolledUser)
        assertTrue((storageManager.getKeysForUserResult("bob") as StorageLoadResult.Success).data.isEmpty())
    }

    @Test
    fun keysRepository_updatesStateFlowOnCrudOperations() = runBlocking {
        assertEquals(StorageOpResult.Success, keysRepository.addKey("Nequi", "300123", "A", "ana", "t1", id = "id-1"))
        assertEquals(1, keysRepository.keys.value.size)
        assertEquals("0.0.1", keysRepository.keys.value.first().version)

        val existing = keysRepository.keys.value.first()
        assertEquals(StorageOpResult.Success, keysRepository.updateKey(existing, "Nequi", "300123", "B", "t2"))
        assertEquals("0.0.2", keysRepository.keys.value.first().version)
        assertEquals("B", keysRepository.keys.value.first().description)

        assertEquals(StorageOpResult.Success, keysRepository.deleteKey("id-1", "ana"))
        assertTrue(keysRepository.keys.value.isEmpty())

        authGateway.biometricAvailable = false
        authGateway.weakBiometricOnly = true
        authGateway.lastLoggedUser = "ana"
        assertEquals(BiometricBannerMode.WEAK_ONLY, authRepository.getBiometricUiStatus().mode)
    }
}
