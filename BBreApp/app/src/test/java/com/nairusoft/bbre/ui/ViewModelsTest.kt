package com.nairusoft.bbre.ui

import com.nairusoft.bbre.data.AuthRepository
import com.nairusoft.bbre.data.KeysRepository
import com.nairusoft.bbre.data.SecureAuthGateway
import com.nairusoft.bbre.model.LoginEvent
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.User
import com.nairusoft.bbre.security.AuthValidator
import com.nairusoft.bbre.security.PasswordHasher
import com.nairusoft.bbre.security.SessionManager
import com.nairusoft.bbre.security.StorageManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.util.Base64

class ViewModelsTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestCryptoProvider : StorageManager.CryptoProvider {
        override fun ensureKey(alias: String): Boolean = true
        override fun encrypt(plainText: String, alias: String): Result<String> =
            Result.success(Base64.getEncoder().encodeToString(plainText.toByteArray(StandardCharsets.UTF_8)))
        override fun decrypt(cipherText: String, alias: String): Result<String> =
            Result.success(String(Base64.getDecoder().decode(cipherText), StandardCharsets.UTF_8))
    }

    private class TestAuthGateway : SecureAuthGateway {
        var biometricAvailable = false
        var weakOnly = false
        var enrolledUser = ""
        var lastUser = ""
        var sessionUser = ""

        override fun createUserCredentials(username: String, password: String): User {
            val c = PasswordHasher.createCredentials(password)
            return User(username, c.hashBase64, c.saltBase64, c.algorithm, c.iterations)
        }

        override fun verifyUserPassword(user: User, password: String): Boolean =
            PasswordHasher.verifyPassword(password, user.passwordHash, user.salt, user.kdfAlgorithm, user.kdfIterations)

        override fun needsPasswordMigration(user: User): Boolean =
            PasswordHasher.needsMigration(user.salt, user.kdfAlgorithm, user.kdfIterations)

        override fun isBiometricAvailable(): Boolean = biometricAvailable
        override fun isWeakBiometricOnly(): Boolean = weakOnly
        override fun hasBiometricEnrollmentFor(username: String): Boolean =
            biometricAvailable && enrolledUser.equals(username, ignoreCase = true)

        override fun clearBiometricEnrollment() {
            enrolledUser = ""
        }

        override fun getLastLoggedInUser(): String = lastUser
        override fun setLastLoggedInUser(username: String) {
            lastUser = username
        }

        override fun clearLastLoggedInUser() {
            lastUser = ""
        }

        override fun getSessionUsername(): String = sessionUser
        override fun setSessionUsername(username: String) {
            sessionUser = username
        }

        override fun clearSessionUsername() {
            sessionUser = ""
        }
    }

    private lateinit var testScope: CoroutineScope
    private lateinit var authRepository: AuthRepository
    private lateinit var keysRepository: KeysRepository
    private lateinit var loginViewModel: LoginViewModel
    private lateinit var dashboardViewModel: DashboardViewModel

    @Before
    fun setUp() {
        testScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val storage = StorageManager(tempFolder.newFolder("bbre_vm"), TestCryptoProvider())
        val gateway = TestAuthGateway()
        val sessionManager = SessionManager(elapsedTimeProvider = { 5_000L })
        authRepository = AuthRepository(storage, gateway, sessionManager)
        keysRepository = KeysRepository(storage)
        loginViewModel = LoginViewModel(authRepository, testScope)
        dashboardViewModel = DashboardViewModel(authRepository, keysRepository, testScope)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun loginViewModel_validatesAndTransitionsRegisterThenLogin() = runBlocking {
        withTimeout(5_000L) {
            loginViewModel.toggleMode()
            assertFalse(loginViewModel.uiState.value.isLoginMode)

            loginViewModel.submitCredentials(" ", "short")
            assertEquals(AuthValidator.UsernameError.EMPTY, loginViewModel.uiState.value.usernameError)
            assertEquals(AuthValidator.PasswordError.TOO_SHORT, loginViewModel.uiState.value.passwordError)

            loginViewModel.onUsernameChanged()
            loginViewModel.onPasswordChanged()
            loginViewModel.submitCredentials("maria", "Segura123")
            assertTrue(loginViewModel.events.first() is LoginEvent.RegistrationSucceeded)
            assertTrue(loginViewModel.uiState.value.isLoginMode)

            loginViewModel.submitCredentials("maria", "WrongPass1")
            val invalidState = loginViewModel.uiState.first { it.isInvalidCredentials }
            assertTrue(invalidState.isInvalidCredentials)

            loginViewModel.submitCredentials("maria", "Segura123")
            assertTrue(loginViewModel.events.first() is LoginEvent.NavigateToDashboard)
        }
    }

    @Test
    fun dashboardViewModel_loadsSavesAndClearsInMemoryKeys() = runBlocking {
        withTimeout(5_000L) {
            authRepository.register("carlos", "Segura123")
            authRepository.login("carlos", "Segura123")

            val activeUser = dashboardViewModel.initializeActiveSession()
            assertEquals("carlos", activeUser)

            val saveResult = CompletableDeferred<StorageOpResult>()
            dashboardViewModel.saveKey(null, "Daviplata", "3112223344", "Ahorros", "2026-02-01 09:00:00") {
                saveResult.complete(it)
            }
            assertEquals(StorageOpResult.Success, saveResult.await())
            assertEquals(1, dashboardViewModel.keys.value.size)

            val generated = dashboardViewModel.generateUniqueCode()
            assertNotNull(generated)
            assertEquals(10, generated.length)

            dashboardViewModel.clearSessionAndInMemoryData()
            assertEquals("", dashboardViewModel.currentUser.value)
            assertTrue(dashboardViewModel.keys.value.isEmpty())
            assertFalse(dashboardViewModel.isSessionValid())
        }
    }
}
