package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
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
        setupBackNavigation()
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

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!isLoginMode) {
                    isLoginMode = true
                    binding.etPassword.text?.clear()
                    updateModeUI()
                } else {
                    finish()
                }
            }
        })
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
            binding.tvSubtitle.text = getString(R.string.login_subtitle)
            binding.tilUsername.hint = getString(R.string.hint_username)
            binding.tilPassword.hint = getString(R.string.hint_password_login)
            binding.btnAction.text = getString(R.string.login_button)
            binding.tvToggleMode.text = getString(R.string.toggle_to_register)

            // Show biometric option only when strong biometric enrollment exists for last logged user
            val savedUser = securityManager.getSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
            if (savedUser.isNotEmpty() && securityManager.hasBiometricEnrollmentFor(savedUser)) {
                binding.divider.visibility = View.VISIBLE
                binding.llBiometric.visibility = View.VISIBLE
                binding.btnBiometric.visibility = View.VISIBLE
                binding.btnBiometric.text = getString(R.string.login_biometric_user, savedUser)
                binding.tvBiometricHint.text = getString(R.string.security_biometric_subtitle)
                binding.tvBiometricHint.setTextColor(getColor(R.color.text_secondary))
            } else if (savedUser.isNotEmpty() && securityManager.isWeakBiometricOnly()) {
                binding.divider.visibility = View.VISIBLE
                binding.llBiometric.visibility = View.VISIBLE
                binding.btnBiometric.visibility = View.GONE
                binding.tvBiometricHint.text = getString(R.string.biometric_weak_only_hint)
                binding.tvBiometricHint.setTextColor(getColor(R.color.text_primary))
            } else {
                binding.divider.visibility = View.GONE
                binding.llBiometric.visibility = View.GONE
            }
        } else {
            binding.tvWelcome.text = getString(R.string.register_title)
            binding.tvSubtitle.text = getString(R.string.register_subtitle)
            binding.tilUsername.hint = getString(R.string.hint_choose_username)
            binding.tilPassword.hint = getString(R.string.hint_password_register)
            binding.btnAction.text = getString(R.string.register_button)
            binding.tvToggleMode.text = getString(R.string.toggle_to_login)
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
            binding.tilUsername.error = getString(R.string.error_username_empty)
            isValid = false
        } else if (username.any { it.isWhitespace() }) {
            binding.tilUsername.error = getString(R.string.error_username_spaces)
            isValid = false
        } else {
            binding.tilUsername.error = null
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = getString(R.string.error_password_empty)
            isValid = false
        } else if (password.any { it.isWhitespace() }) {
            binding.tilPassword.error = getString(R.string.error_password_spaces)
            isValid = false
        } else if (password.length < 8) {
            binding.tilPassword.error = getString(R.string.error_password_min_length)
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
                        binding.tilPassword.error = getString(R.string.error_invalid_credentials)
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
                    Toast.makeText(this@LoginActivity, getString(R.string.register_success), Toast.LENGTH_LONG).show()
                    isLoginMode = true
                    updateModeUI()
                    binding.etPassword.text?.clear()
                }
                is StorageOpResult.Duplicate -> {
                    binding.tilUsername.error = getString(R.string.error_username_taken)
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
        applyFadeCloseTransition()
    }

    private fun promptBiometricEnrollment(username: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.biometric_enroll_title))
            .setMessage(getString(R.string.biometric_enroll_message, username))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.biometric_enroll_confirm)) { _, _ ->
                showBiometricEnrollmentPrompt(username)
            }
            .setNegativeButton(getString(R.string.biometric_enroll_later)) { _, _ ->
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
                Toast.makeText(this, getString(R.string.biometric_enroll_unavailable), Toast.LENGTH_SHORT).show()
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
                        Toast.makeText(
                            this@LoginActivity,
                            getString(R.string.biometric_enroll_success, username),
                            Toast.LENGTH_SHORT
                        ).show()
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
            .setSubtitle(getString(R.string.biometric_enroll_prompt_subtitle))
            .setNegativeButtonText(getString(R.string.btn_cancel))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showBiometricPrompt() {
        val lastUser = securityManager.getSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
        if (lastUser.isEmpty() || !securityManager.hasBiometricEnrollmentFor(lastUser)) {
            binding.tvBiometricHint.text = getString(R.string.biometric_login_first)
            binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
            return
        }

        val cipher = when (val cipherResult = securityManager.createBiometricDecryptCipher()) {
            is SecurityManager.BiometricCipherResult.Ready -> cipherResult.cipher
            is SecurityManager.BiometricCipherResult.KeyInvalidated -> {
                securityManager.clearBiometricEnrollment()
                binding.btnBiometric.visibility = View.GONE
                binding.tvBiometricHint.text = getString(R.string.biometric_invalidated)
                binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                return
            }
            is SecurityManager.BiometricCipherResult.Unavailable -> {
                binding.tvBiometricHint.text = getString(R.string.biometric_unavailable)
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
                        binding.tvBiometricHint.text = getString(R.string.biometric_invalid_credential)
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
                                    binding.tvBiometricHint.text = getString(R.string.biometric_success)
                                    binding.tvBiometricHint.setTextColor(getColor(R.color.green_success))
                                    delay(250)
                                    navigateToDashboard()
                                } else {
                                    securityManager.clearBiometricEnrollment()
                                    binding.btnBiometric.visibility = View.GONE
                                    binding.tvBiometricHint.text = getString(R.string.biometric_user_missing)
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
                    binding.tvBiometricHint.text = getString(R.string.biometric_error_format, errString)
                    binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    binding.tvBiometricHint.text = getString(R.string.biometric_failed)
                    binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
                }
            }
        )

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_biometric_title))
            .setSubtitle(getString(R.string.security_biometric_subtitle))
            .setNegativeButtonText(getString(R.string.biometric_use_password))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }
}
