package com.nairusoft.bbre.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityTransferBinding

/**
 * Transfer Activity - Send money using BBre keys
 */
class TransferActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTransferBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTransferBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupTransferForm()
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.transfer_title)
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupTransferForm() {
        // Setup key type selector
        // Setup amount input with validation
        // Setup recipient info
        
        binding.btnTransfer.setOnClickListener {
            // Validate and process transfer
        }
    }
}
