package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityRegisterBinding
import com.nairusoft.bbre.data.UserManager
import kotlinx.coroutines.launch

/**
 * Register Activity - Simple user registration
 */
class RegisterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityRegisterBinding
    private lateinit var userManager: UserManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRegisterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager.getInstance(this)

        setupUI()
        setupRegisterButton()
        setupLoginLink()
    }

    private fun setupUI() {
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setDisplayShowHomeEnabled(true)
    }

    private fun setupRegisterButton() {
        binding.btnRegister.setOnClickListener {
            val username = binding.etUsername.text.toString().trim()
            val password = binding.etPassword.text.toString().trim()
            val confirmPassword = binding.etConfirmPassword.text.toString().trim()

            if (validateInput(username, password, confirmPassword)) {
                performRegister(username, password)
            }
        }
    }

    private fun setupLoginLink() {
        binding.tvLogin.setOnClickListener {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
        }
    }

    private fun validateInput(username: String, password: String, confirmPassword: String): Boolean {
        var isValid = true

        if (username.isEmpty()) {
            binding.tilUsername.error = "Ingresa un nombre de usuario"
            isValid = false
        } else if (username.length < 3) {
            binding.tilUsername.error = "El usuario debe tener al menos 3 caracteres"
            isValid = false
        } else {
            binding.tilUsername.error = null
        }

        if (password.isEmpty()) {
            binding.tilPassword.error = "Ingresa una contraseña"
            isValid = false
        } else if (password.length != 8) {
            binding.tilPassword.error = getString(R.string.error_password_length)
            isValid = false
        } else {
            binding.tilPassword.error = null
        }

        if (confirmPassword.isEmpty()) {
            binding.tilConfirmPassword.error = "Confirma tu contraseña"
            isValid = false
        } else if (password != confirmPassword) {
            binding.tilConfirmPassword.error = getString(R.string.error_passwords_match)
            isValid = false
        } else {
            binding.tilConfirmPassword.error = null
        }

        return isValid
    }

    private fun performRegister(username: String, password: String) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnRegister.isEnabled = false

        lifecycleScope.launch {
            kotlinx.coroutines.delay(1000)

            val success = userManager.registerUser(username, password)

            if (success) {
                // Auto login after registration
                startActivity(Intent(this@RegisterActivity, LoginActivity::class.java))
                finish()
            } else {
                binding.tilUsername.error = getString(R.string.error_username_exists)
                binding.progressBar.visibility = View.GONE
                binding.btnRegister.isEnabled = true
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }
}
