package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding
import com.nairusoft.bbre.security.SecurityManager

/**
 * Dashboard Activity - Main screen after login
 * Shows options: Mis Llaves and Configuración
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var securityManager: SecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = SecurityManager.getInstance(this)

        setupToolbar()
        setupWelcomeMessage()
        setupActionButtons()
    }

    private fun setupToolbar() {
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setIcon(R.drawable.logo_bbre)
    }

    private fun setupWelcomeMessage() {
        val currentUser = securityManager.getSecureData("current_user", "Usuario")
        binding.tvWelcomeUser.text = "Bienvenido, $currentUser"
    }

    private fun setupActionButtons() {
        binding.btnMyKeys.setOnClickListener {
            startActivity(Intent(this, MyKeysActivity::class.java))
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    override fun onBackPressed() {
        moveTaskToBack(true)
    }
}
