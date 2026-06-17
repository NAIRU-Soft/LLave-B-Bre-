package com.nairusoft.bbre.ui

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
import kotlinx.coroutines.launch

/**
 * Login Activity - Secure authentication with biometric support
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var securityManager: SecurityManager
    private val BIOMETRIC_KEY_ALIAS = "bbre_biometric_key"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = SecurityManager.getInstance(this)

        setupUI()
        setupBiometricButton()
        setupLoginButton()
    }

    private fun setupUI() {
        // Set up input fields with security features
        binding.etPhoneOrEmail.hint = getString(R.string.login_phone_hint)
        binding.etPin.hint = getString(R.string.login_pin_hint)
        
        // Show/hide PIN toggle could be added here
    }

    private fun setupBiometricButton() {
        if (securityManager.isBiometricAvailable()) {
            binding.btnBiometric.visibility = View.VISIBLE
            
            // Generate biometric key if not exists
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
            val identifier = binding.etPhoneOrEmail.text.toString().trim()
            val pin = binding.etPin.text.toString().trim()
            
            if (validateInput(identifier, pin)) {
                performLogin(identifier, pin)
            }
        }
        
        binding.tvForgotPin.setOnClickListener {
            // TODO: Implement PIN recovery flow
        }
    }

    private fun validateInput(identifier: String, pin: String): Boolean {
        var isValid = true
        
        if (identifier.isEmpty()) {
            binding.tilPhoneOrEmail.error = "Ingresa tu celular, correo o cédula"
            isValid = false
        } else {
            binding.tilPhoneOrEmail.error = null
        }
        
        if (pin.isEmpty()) {
            binding.tilPin.error = "Ingresa tu PIN"
            isValid = false
        } else if (pin.length < 4) {
            binding.tilPin.error = "El PIN debe tener al menos 4 dígitos"
            isValid = false
        } else {
            binding.tilPin.error = null
        }
        
        return isValid
    }

    private fun performLogin(identifier: String, pin: String) {
        // In a real app, this would verify credentials with the server
        // For now, we'll simulate successful login after validation
        
        binding.progressBar.visibility = View.VISIBLE
        binding.btnLogin.isEnabled = false
        
        lifecycleScope.launch {
            // Simulate network call
            kotlinx.coroutines.delay(1500)
            
            // Hash the PIN for secure storage/comparison
            val pinHash = securityManager.hashData(pin)
            
            // Store encrypted session token
            securityManager.storeSecureData("session_identifier", identifier)
            securityManager.storeSecureData("session_pin_hash", pinHash)
            
            // Navigate to dashboard
            startActivity(android.content.Intent(this@LoginActivity, DashboardActivity::class.java))
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        }
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        
        val biometricPrompt = BiometricPrompt(this, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    
                    // Biometric authentication succeeded
                    // In a real app, decrypt stored credentials and log in
                    binding.tvBiometricHint.text = "¡Autenticación exitosa!"
                    binding.tvBiometricHint.setTextColor(getColor(R.color.green_success))
                    
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(1000)
                        startActivity(android.content.Intent(this@LoginActivity, DashboardActivity::class.java))
                        finish()
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
            .setNegativeButtonText("Usar PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG or
                    BiometricManager.Authenticators.BIOMETRIC_WEAK)
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    override fun onBackPressed() {
        // Show exit confirmation or minimize app
        super.onBackPressed()
    }
}
