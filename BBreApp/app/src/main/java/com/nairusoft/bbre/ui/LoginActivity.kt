package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityLoginBinding
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.SessionManager
import com.nairusoft.bbre.security.StorageLoadResult
import com.nairusoft.bbre.security.StorageManager
import com.nairusoft.bbre.security.StorageOpResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Login Activity - Secure authentication with biometric support and simple credentials
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var securityManager: SecurityManager
    private lateinit var storageManager: StorageManager
    private val sessionManager: SessionManager = SessionManager.getInstance()
    
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyWindowInsets()

        securityManager = SecurityManager.getInstance(this)
        storageManager = StorageManager.getInstance(this)

        // Ensure no stale session remains when entering LoginActivity
        sessionManager.clearSession()
        securityManager.removeSecureData(SessionManager.KEY_SESSION_USERNAME)

        setupInputListeners()
        updateModeUI()
        setupToggleMode()
        setupBiometricButton()
        setupActionButton()
    }

    private fun applyWindowInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.scrollRoot) { view, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime()
            )
            view.updatePadding(
                left = insets.left,
                top = insets.top,
                right = insets.right,
                bottom = insets.bottom
            )
            windowInsets
        }
    }

    private fun setupInputListeners() {
        binding.etUsername.doAfterTextChanged {
            binding.tilUsername.error = null
        }
        binding.etPassword.doAfterTextChanged {
            binding.tilPassword.error = null
        }
        binding.etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitAuthForm()
                true
            } else {
                false
            }
        }
    }

    private fun updateModeUI() {
        if (isLoginMode) {
            binding.tvWelcome.text = getString(R.string.login_title)
            binding.tvSubtitle.text = "Inicia sesión con tu usuario y contraseña"
            binding.tilUsername.hint = "Nombre de usuario"
            binding.tilPassword.hint = "Contraseña"
            binding.btnAction.text = "Ingresar"
            binding.tvToggleMode.text = "¿No tienes una cuenta? Regístrate"

            // Show biometric option only when strong biometric enrollment exists for last logged user
            val savedUser = securityManager.getSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
            if (savedUser.isNotEmpty() && securityManager.hasBiometricEnrollmentFor(savedUser)) {
                binding.divider.visibility = View.VISIBLE
                binding.llBiometric.visibility = View.VISIBLE
                binding.btnBiometric.visibility = View.VISIBLE
                binding.btnBiometric.text = "Ingresar con huella ($savedUser)"
                binding.tvBiometricHint.text = getString(R.string.security_biometric_subtitle)
                binding.tvBiometricHint.setTextColor(getColor(R.color.text_secondary))
            } else if (savedUser.isNotEmpty() && securityManager.isWeakBiometricOnly()) {
                binding.divider.visibility = View.VISIBLE
                binding.llBiometric.visibility = View.VISIBLE
                binding.btnBiometric.visibility = View.GONE
                binding.tvBiometricHint.text = "Este dispositivo solo tiene biometría débil; usa tu contraseña"
                binding.tvBiometricHint.setTextColor(getColor(R.color.text_primary))
            } else {
                binding.divider.visibility = View.GONE
                binding.llBiometric.visibility = View.GONE
            }
        } else {
            binding.tvWelcome.text = "Crear Cuenta"
            binding.tvSubtitle.text = "Regístrate de forma sencilla"
            binding.tilUsername.hint = "Elige un usuario"
            binding.tilPassword.hint = "Contraseña (mínimo 8 caracteres)"
            binding.btnAction.text = "Registrar"
            binding.tvToggleMode.text = "¿Ya tienes una cuenta? Inicia sesión"
            binding.divider.visibility = View.GONE
            binding.llBiometric.visibility = View.GONE
        }

        binding.tilUsername.error = null
        binding.tilPassword.error = null
    }

    private fun setupToggleMode() {
        binding.tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode
            binding.etPassword.text?.clear()
            updateModeUI()
        }
    }

    private fun setupBiometricButton() {
        binding.btnBiometric.setOnClickListener {
            showBiometricPrompt()
        }
    }

    private fun setupActionButton() {
        binding.btnAction.setOnClickListener {
            submitAuthForm()
        }
    }

    private fun submitAuthForm() {
        val username = binding.etUsername.text?.toString()?.trim() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""

        if (validateInput(username, password)) {
            if (isLoginMode) {
                performLogin(username, password)
            } else {
                performRegister(username, password)
            }
        }
    }

    private fun validateInput(username: String, password: String): Boolean {
        var isValid = true

        if (username.isEmpty()) {
            binding.tilUsername.error = "Ingresa tu nombre de usuario"
            isValid = false
        } else if (username.any { it.isWhitespace() }) {
            binding.tilUsername.error = "El nombre de usuario no debe tener espacios"
            isValid = false
        } else {
            binding.tilUsername.error = null
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = "Ingresa tu contraseña"
            isValid = false
        } else if (password.any { it.isWhitespace() }) {
            binding.tilPassword.error = "La contraseña no debe contener espacios"
            isValid = false
        } else if (password.length < 8) {
            binding.tilPassword.error = "La contraseña debe tener al menos 8 caracteres"
            isValid = false
        } else {
            binding.tilPassword.error = null
        }

        return isValid
    }

    private fun performLogin(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnAction.isEnabled = false

        lifecycleScope.launch {
            when (val usersResult = storageManager.getUsersResult()) {
                is StorageLoadResult.Error -> {
                    binding.progressBar.visibility = View.GONE
                    binding.btnAction.isEnabled = true
                    Toast.makeText(this@LoginActivity, usersResult.message, Toast.LENGTH_LONG).show()
                }
                is StorageLoadResult.Success -> {
                    val foundUser = usersResult.data.find { it.username.equals(username, ignoreCase = true) }

                    if (foundUser != null && securityManager.verifyUserPassword(foundUser, password)) {
                        // Migrate legacy SHA-256 user to salted PBKDF2 on first successful login
                        if (securityManager.needsPasswordMigration(foundUser)) {
                            val migratedUser = securityManager.createUserCredentials(foundUser.username, password)
                            storageManager.updateUser(foundUser.username, migratedUser)
                        }

                        // Successful password login
                        sessionManager.startSession(foundUser.username)
                        securityManager.storeSecureData(SessionManager.KEY_SESSION_USERNAME, foundUser.username)
                        securityManager.storeSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER, foundUser.username)

                        // Clear stale biometric binding if a different account logged in
                        if (!securityManager.hasBiometricEnrollmentFor(foundUser.username)) {
                            securityManager.clearBiometricEnrollment()
                        }

                        if (securityManager.isBiometricAvailable() &&
                            !securityManager.hasBiometricEnrollmentFor(foundUser.username)
                        ) {
                            binding.progressBar.visibility = View.GONE
                            binding.btnAction.isEnabled = true
                            promptBiometricEnrollment(foundUser.username)
                        } else {
                            navigateToDashboard()
                        }
                    } else {
                        binding.progressBar.visibility = View.GONE
                        binding.btnAction.isEnabled = true
                        binding.tilPassword.error = "Usuario o contraseña incorrectos"
                    }
                }
            }
        }
    }

    private fun performRegister(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnAction.isEnabled = false

        lifecycleScope.launch {
            val newUser = securityManager.createUserCredentials(username, password)
            val result = storageManager.addUser(newUser)

            binding.progressBar.visibility = View.GONE
            binding.btnAction.isEnabled = true

            when (result) {
                is StorageOpResult.Success -> {
                    Toast.makeText(this@LoginActivity, "Registro exitoso. Ahora puedes iniciar sesión.", Toast.LENGTH_LONG).show()
                    isLoginMode = true
                    updateModeUI()
                    binding.etPassword.text?.clear()
                }
                is StorageOpResult.Duplicate -> {
                    binding.tilUsername.error = "El usuario ya está registrado"
                }
                is StorageOpResult.Error -> {
                    Toast.makeText(this@LoginActivity, result.message, Toast.LENGTH_LONG).show()
                }
                is StorageOpResult.NotFound -> Unit
            }
        }
    }

    private fun navigateToDashboard() {
        startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun promptBiometricEnrollment(username: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Activar ingreso biométrico")
            .setMessage("¿Deseas vincular tu huella o biometría fuerte para ingresar como $username?")
            .setCancelable(false)
            .setPositiveButton("Activar") { _, _ ->
                showBiometricEnrollmentPrompt(username)
            }
            .setNegativeButton("Ahora no") { _, _ ->
                navigateToDashboard()
            }
            .show()
    }

    private fun showBiometricEnrollmentPrompt(username: String) {
        val cipherResult = securityManager.createBiometricEncryptCipher()
        val cipher = when (cipherResult) {
            is SecurityManager.BiometricCipherResult.Ready -> cipherResult.cipher
            is SecurityManager.BiometricCipherResult.KeyInvalidated,
            is SecurityManager.BiometricCipherResult.Unavailable -> {
                Toast.makeText(this, "No se pudo inicializar la biometría fuerte", Toast.LENGTH_SHORT).show()
                navigateToDashboard()
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    val authCipher = result.cryptoObject?.cipher
                    if (authCipher != null && securityManager.saveBiometricEnrollment(username, authCipher)) {
                        Toast.makeText(this@LoginActivity, "Biometría activada para $username", Toast.LENGTH_SHORT).show()
                    }
                    navigateToDashboard()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    navigateToDashboard()
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_biometric_title))
            .setSubtitle("Confirma tu biometría para vincularla a tu cuenta")
            .setNegativeButtonText("Cancelar")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showBiometricPrompt() {
        val lastUser = securityManager.getSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
        if (lastUser.isEmpty() || !securityManager.hasBiometricEnrollmentFor(lastUser)) {
            binding.tvBiometricHint.text = "Inicia sesión con contraseña primero"
            binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
            return
        }

        val cipher = when (val cipherResult = securityManager.createBiometricDecryptCipher()) {
            is SecurityManager.BiometricCipherResult.Ready -> cipherResult.cipher
            is SecurityManager.BiometricCipherResult.KeyInvalidated -> {
                securityManager.clearBiometricEnrollment()
                binding.btnBiometric.visibility = View.GONE
                binding.tvBiometricHint.text = "La biometría del dispositivo cambió. Inicia con contraseña para reactivarla."
                binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                return
            }
            is SecurityManager.BiometricCipherResult.Unavailable -> {
                binding.tvBiometricHint.text = "Biometría no disponible; usa tu contraseña"
                binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
                return
            }
        }

        val executor = ContextCompat.getMainExecutor(this)
        val biometricPrompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)

                    val authCipher = result.cryptoObject?.cipher
                    val decryptedUser = authCipher?.let { securityManager.verifyBiometricDecryption(it) }
                    if (decryptedUser.isNullOrEmpty()) {
                        securityManager.clearBiometricEnrollment()
                        binding.btnBiometric.visibility = View.GONE
                        binding.tvBiometricHint.text = "Credencial biométrica inválida. Usa tu contraseña."
                        binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                        return
                    }

                    lifecycleScope.launch {
                        when (val usersResult = storageManager.getUsersResult()) {
                            is StorageLoadResult.Error -> {
                                binding.tvBiometricHint.text = usersResult.message
                                binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                            }
                            is StorageLoadResult.Success -> {
                                val existingUser = usersResult.data.find {
                                    it.username.equals(decryptedUser, ignoreCase = true)
                                }
                                if (existingUser != null) {
                                    sessionManager.startSession(existingUser.username)
                                    securityManager.storeSecureData(
                                        SessionManager.KEY_SESSION_USERNAME,
                                        existingUser.username
                                    )
                                    binding.tvBiometricHint.text = "¡Autenticación exitosa!"
                                    binding.tvBiometricHint.setTextColor(getColor(R.color.green_success))
                                    delay(300)
                                    navigateToDashboard()
                                } else {
                                    securityManager.clearBiometricEnrollment()
                                    binding.btnBiometric.visibility = View.GONE
                                    binding.tvBiometricHint.text = "El usuario ya no existe. Inicia sesión o regístrate."
                                    binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                                }
                            }
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        return
                    }
                    binding.tvBiometricHint.text = "Error: $errString"
                    binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    binding.tvBiometricHint.text = "Biometría no reconocida. Intenta de nuevo."
                    binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_biometric_title))
            .setSubtitle(getString(R.string.security_biometric_subtitle))
            .setNegativeButtonText("Usar Contraseña")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    override fun onBackPressed() {
        if (!isLoginMode) {
            isLoginMode = true
            updateModeUI()
        } else {
            super.onBackPressed()
        }
    }
}
