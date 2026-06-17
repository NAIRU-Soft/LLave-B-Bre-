package com.nairusoft.bbre.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityQrScannerBinding

/**
 * QR Scanner Activity - Scan BBre QR codes for payments
 */
class QRScannerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityQrScannerBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityQrScannerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        // TODO: Initialize camera and QR scanner
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.qr_title)
        
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }
}
