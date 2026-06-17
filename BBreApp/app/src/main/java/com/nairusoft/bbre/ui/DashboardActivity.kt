package com.nairusoft.bbre.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding

/**
 * Dashboard Activity - Main screen after login
 * Shows balance, transfer options, and QR scanner
 */
class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBalanceCard()
        setupActionButtons()
    }

    private fun setupToolbar() {
        // Setup toolbar with BBre logo
        supportActionBar?.setDisplayShowHomeEnabled(true)
        supportActionBar?.setIcon(R.drawable.logo_bbre)
    }

    private fun setupBalanceCard() {
        // Display available balance
        // In a real app, this would fetch from the server
        binding.tvBalance.text = "$ 0.00 COP"
    }

    private fun setupActionButtons() {
        binding.btnTransfer.setOnClickListener {
            startActivity(android.content.Intent(this, TransferActivity::class.java))
        }

        binding.btnQR.setOnClickListener {
            // TODO: Launch QR Scanner
        }

        binding.btnHistory.setOnClickListener {
            // TODO: Show transaction history
        }

        binding.btnSettings.setOnClickListener {
            // TODO: Open settings
        }
    }

    override fun onBackPressed() {
        // Show exit confirmation or minimize to background
        super.onBackPressed()
    }
}
