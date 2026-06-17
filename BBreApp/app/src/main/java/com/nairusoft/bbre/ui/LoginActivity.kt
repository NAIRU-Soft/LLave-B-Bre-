package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityLoginBinding
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.StorageManager
import com.nairusoft.bbre.security.User
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Login Activity - Secure authentication with biometric support and simple credentials
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var securityManager: SecurityManager
    private lateinit var storageManager: StorageManager
    private val BIOMETRIC_KEY_ALIAS = "bbre_biometric_key"
    
    private var isLoginMode = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = SecurityManager.getInstance(this)
        storageManager = StorageManager.getInstance(this)

        updateModeUI()
        setupToggleMode()
        setupBiometricButton()
        setupActionButton()
    }

    private fun updateModeUI() {
        if (isLoginMode) {
            binding.tvWelcome.text = getString(R.string.login_title)
            binding.tvSubtitle.text = getString(R.string.login_subtitle)
            binding.tilUsername.hint = "Nombre de usuario"
            binding.tilPassword.hint = "Contraseña"
            binding.btnAction.text = "Ingresar"
            binding.tvToggleMode.text = "¿No tienes una cuenta? Regístrate"
            
            // Show biometric option if a previous session exists
            val savedUser = securityManager.getSecureData("session_username")
            if (savedUser.isNotEmpty() && securityManager.isBiometricAvailable()) {
                binding.llBiometric.visibility = View.VISIBLE
            } else {
                binding.llBiometric.visibility = View.GONE
            }
        } else {
            binding.tvWelcome.text = "Crear Cuenta"
            binding.tvSubtitle.text = "Regístrate de forma sencilla"
            binding.tilUsername.hint = "Elige un usuario"
            binding.tilPassword.hint = "Contraseña (mínimo 8 caracteres)"
            binding.btnAction.text = "Registrar"
            binding.tvToggleMode.text = "¿Ya tienes una cuenta? Inicia sesión"
            binding.llBiometric.visibility = View.GONE
        }
        
        binding.tilUsername.error = null
        binding.tilPassword.error = null
    }

    private fun setupToggleMode() {
        binding.tvToggleMode.setOnClickListener {
            isLoginMode = !isLoginMode
            updateModeUI()
        }
    }

    private fun setupBiometricButton() {
        if (securityManager.isBiometricAvailable()) {
            // Generate biometric key if not exists
            lifecycleScope.launch {
                securityManager.generateBiometricKey(BIOMETRIC_KEY_ALIAS)
            }
            
            binding.btnBiometric.setOnClickListener {
                showBiometricPrompt()
            }
        }
    }

    private fun setupActionButton() {
        binding.btnAction.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            
            if (validateInput(username, password)) {
                if (isLoginMode) {
                    performLogin(username, password)
                } else {
                    performRegister(username, password)
                }
            }
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
            delay(1000) // Simulate network/crypto operation delay
            
            val users = storageManager.getUsers()
            val foundUser = users.find { it.username.equals(username, ignoreCase = true) }
            val inputHash = securityManager.hashData(password)

            if (foundUser != null && foundUser.passwordHash == inputHash) {
                // Successful login
                securityManager.storeSecureData("session_username", foundUser.username)
                
                startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            } else {
                binding.progressBar.visibility = View.GONE
                binding.btnAction.isEnabled = true
                binding.tilPassword.error = "Usuario o contraseña incorrectos"
            }
        }
    }

    private fun performRegister(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnAction.isEnabled = false
        
        lifecycleScope.launch {
            delay(1000)
            
            val passwordHash = securityManager.hashData(password)
            val newUser = User(username, passwordHash)
            val success = storageManager.addUser(newUser)
            
            binding.progressBar.visibility = View.GONE
            binding.btnAction.isEnabled = true
            
            if (success) {
                Toast.makeText(this@LoginActivity, "Registro exitoso. Ahora puedes iniciar sesión.", Toast.LENGTH_LONG).show()
                isLoginMode = true
                updateModeUI()
                binding.etPassword.text?.clear()
            } else {
                binding.tilUsername.error = "El usuario ya está registrado"
            }
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    val savedUser = securityManager.getSecureData("session_username")
                    if (savedUser.isNotEmpty()) {
                        binding.tvBiometricHint.text = "¡Autenticación exitosa!"
                        binding.tvBiometricHint.setTextColor(getColor(R.color.green_success))
                        
                        lifecycleScope.launch {
                            delay(800)
                            startActivity(Intent(this@LoginActivity, DashboardActivity::class.java))
                            finish()
                        }
                    } else {
                        binding.tvBiometricHint.text = "Inicia sesión con contraseña primero"
                        binding.tvBiometricHint.setTextColor(getColor(R.color.orange_warning))
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
            .setNegativeButtonText("Usar Contraseña")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
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
