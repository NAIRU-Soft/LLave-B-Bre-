package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityLoginBinding
import com.nairusoft.bbre.model.BiometricBannerMode
import com.nairusoft.bbre.model.LoginEvent
import com.nairusoft.bbre.model.LoginUiState
import com.nairusoft.bbre.security.AuthValidator
import com.nairusoft.bbre.security.SecurityManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Login Activity - Renders auth UI and hardware BiometricPrompt delegating state and storage to [LoginViewModel].
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var securityManager: SecurityManager
    private val viewModel: LoginViewModel by viewModels { LoginViewModel.factory(this) }

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

        if (savedInstanceState == null) {
            viewModel.initializeScreen()
        } else {
            viewModel.refreshBiometricStatus()
        }

        setupInputListeners()
        setupBackNavigation()
        setupToggleMode()
        setupBiometricButton()
        setupActionButton()
        observeViewModel()
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
                if (!viewModel.uiState.value.isLoginMode) {
                    binding.etPassword.text?.clear()
                    viewModel.setMode(true)
                } else {
                    finish()
                }
            }
        })
    }

    private fun setupInputListeners() {
        binding.etUsername.doAfterTextChanged {
            viewModel.onUsernameChanged()
        }
        binding.etPassword.doAfterTextChanged {
            viewModel.onPasswordChanged()
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

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { renderState(it) }
                }
                launch {
                    viewModel.events.collect { handleEvent(it) }
                }
            }
        }
    }

    private fun renderState(state: LoginUiState) {
        if (state.isLoginMode) {
            binding.tvWelcome.text = getString(R.string.login_title)
            binding.tvSubtitle.text = getString(R.string.login_subtitle)
            binding.tilUsername.hint = getString(R.string.hint_username)
            binding.tilPassword.hint = getString(R.string.hint_password_login)
            binding.btnAction.text = getString(R.string.login_button)
            binding.tvToggleMode.text = getString(R.string.toggle_to_register)

            when (state.biometricStatus.mode) {
                BiometricBannerMode.STRONG_AVAILABLE -> {
                    binding.divider.visibility = View.VISIBLE
                    binding.llBiometric.visibility = View.VISIBLE
                    binding.btnBiometric.visibility = View.VISIBLE
                    binding.btnBiometric.text = getString(
                        R.string.login_biometric_user,
                        state.biometricStatus.savedUsername
                    )
                    binding.tvBiometricHint.text = getString(R.string.security_biometric_subtitle)
                    binding.tvBiometricHint.setTextColor(getColor(R.color.text_secondary))
                }
                BiometricBannerMode.WEAK_ONLY -> {
                    binding.divider.visibility = View.VISIBLE
                    binding.llBiometric.visibility = View.VISIBLE
                    binding.btnBiometric.visibility = View.GONE
                    binding.tvBiometricHint.text = getString(R.string.biometric_weak_only_hint)
                    binding.tvBiometricHint.setTextColor(getColor(R.color.text_primary))
                }
                BiometricBannerMode.HIDDEN -> {
                    binding.divider.visibility = View.GONE
                    binding.llBiometric.visibility = View.GONE
                }
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

        binding.progressBar.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        binding.btnAction.isEnabled = !state.isLoading

        binding.tilUsername.error = when {
            state.isUsernameTaken -> getString(R.string.error_username_taken)
            state.usernameError == AuthValidator.UsernameError.EMPTY -> getString(R.string.error_username_empty)
            state.usernameError == AuthValidator.UsernameError.CONTAINS_WHITESPACE -> getString(R.string.error_username_spaces)
            else -> null
        }

        binding.tilPassword.error = when {
            state.isInvalidCredentials -> getString(R.string.error_invalid_credentials)
            state.passwordError == AuthValidator.PasswordError.EMPTY -> getString(R.string.error_password_empty)
            state.passwordError == AuthValidator.PasswordError.CONTAINS_WHITESPACE -> getString(R.string.error_password_spaces)
            state.passwordError == AuthValidator.PasswordError.TOO_SHORT -> getString(R.string.error_password_min_length)
            else -> null
        }
    }

    private fun handleEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.NavigateToDashboard -> navigateToDashboard()
            is LoginEvent.PromptBiometricEnrollment -> promptBiometricEnrollment(event.username)
            is LoginEvent.RegistrationSucceeded -> {
                binding.etPassword.text?.clear()
                Toast.makeText(this, getString(R.string.register_success), Toast.LENGTH_LONG).show()
            }
            is LoginEvent.ShowMessage -> {
                Toast.makeText(this, event.message, Toast.LENGTH_LONG).show()
            }
            is LoginEvent.BiometricLoginSucceeded -> {
                binding.tvBiometricHint.text = getString(R.string.biometric_success)
                binding.tvBiometricHint.setTextColor(getColor(R.color.green_success))
                lifecycleScope.launch {
                    delay(200)
                    navigateToDashboard()
                }
            }
            is LoginEvent.BiometricUserMissing -> {
                binding.btnBiometric.visibility = View.GONE
                binding.tvBiometricHint.text = getString(R.string.biometric_user_missing)
                binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
            }
            is LoginEvent.BiometricLoadError -> {
                binding.tvBiometricHint.text = event.message
                binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
            }
        }
    }

    private fun setupToggleMode() {
        binding.tvToggleMode.setOnClickListener {
            binding.etPassword.text?.clear()
            viewModel.toggleMode()
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
        val rawUsername = binding.etUsername.text?.toString() ?: ""
        val password = binding.etPassword.text?.toString() ?: ""
        viewModel.submitCredentials(rawUsername, password)
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
        val savedUser = viewModel.uiState.value.biometricStatus.savedUsername
        if (savedUser.isEmpty() || !securityManager.hasBiometricEnrollmentFor(savedUser)) {
            binding.tvBiometricHint.text = getString(R.string.biometric_login_first)
            binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
            return
        }

        val cipher = when (val cipherResult = securityManager.createBiometricDecryptCipher()) {
            is SecurityManager.BiometricCipherResult.Ready -> cipherResult.cipher
            is SecurityManager.BiometricCipherResult.KeyInvalidated -> {
                securityManager.clearBiometricEnrollment()
                viewModel.refreshBiometricStatus()
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
                        viewModel.refreshBiometricStatus()
                        binding.btnBiometric.visibility = View.GONE
                        binding.tvBiometricHint.text = getString(R.string.biometric_invalid_credential)
                        binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                        return
                    }

                    viewModel.completeBiometricLogin(decryptedUser)
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
