package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityLoginBinding
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.data.UserManager
import kotlinx.coroutines.launch

/**
 * Login Activity - Simple authentication with username/password and biometric support
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var securityManager: SecurityManager
    private lateinit var userManager: UserManager
    private val BIOMETRIC_KEY_ALIAS = "bbre_biometric_key"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = SecurityManager.getInstance(this)
        userManager = UserManager.getInstance(this)

        setupUI()
        setupBiometricButton()
        setupLoginButton()
        setupRegisterLink()
    }

    private fun setupUI() {
        binding.etUsername.hint = getString(R.string.login_username_hint)
        binding.etPassword.hint = getString(R.string.login_password_hint)
    }

    private fun setupBiometricButton() {
        if (securityManager.isBiometricAvailable()) {
            binding.btnBiometric.visibility = View.VISIBLE
            
            lifecycleScope.launch {
                securityManager.generateBiometricKey(BIOMETRIC_KEY_ALIAS)
            }
            
            binding.btnBiometric.setOnClickListener {
                showBiometricPrompt()
            }
        } else {
            binding.btnBiometric.visibility = View.GONE
            binding.tvBiometricHint.visibility = View.GONE
        }
    }

    private fun setupLoginButton() {
        binding.btnLogin.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            
            if (validateInput(username, password)) {
                performLogin(username, password)
            }
        }
    }

    private fun setupRegisterLink() {
        binding.tvRegister.setOnClickListener {
            startActivity(Intent(this, RegisterActivity::class.java))
            finish()
        }
    }

    private fun validateInput(username: String, password: String): Boolean {
        var isValid = true
        
        if (username.isEmpty()) {
            binding.tilUsername.error = "Ingresa tu nombre de usuario"
            isValid = false
        } else {
            binding.tilUsername.error = null
        }
        
        if (password.isEmpty()) {
            binding.tilPassword.error = "Ingresa tu contraseña"
            isValid = false
        } else if (password.length != 8) {
            binding.tilPassword.error = getString(R.string.error_password_length)
            isValid = false
        } else {
            binding.tilPassword.error = null
        }
        
        return isValid
    }

    private fun performLogin(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        
        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)
            
            val user = userManager.authenticate(username, password)
            
            if (user != null) {
                securityManager.storeSecureData("current_user", username)
                
                startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                binding.tilUsername.error = getString(R.string.error_invalid_credentials)
                binding.progressBar.visibility = View.GONE
                binding.btnLogin.isEnabled = true
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    binding.tvBiometricHint.text = "¡Autenticación exitosa!"
                    binding.tvBiometricHint.setTextColor(getColor(R.color.green_success))
                    
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(1000)
                        
                        val currentUser = securityManager.getSecureData("current_user", "")
                        if (currentUser.isNotEmpty()) {
                            startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                            finish()
                        } else {
                            binding.tvBiometricHint.text = "Primero debes iniciar sesión"
                        }
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    
                    if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        return
                    }
                    
                    binding.tvBiometricHint.text = "Error: $errString"
                    binding.tvBiometricHint.setTextColor(getColor(R.color.red_error))
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    binding.tvBiometricHint.text = "Huella no reconocida. Intenta de nuevo."
                    binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.security_biometric_title))
            .setSubtitle(getString(R.string.security_biometric_subtitle))
            .setNegativeButtonText("Usar contraseña")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
