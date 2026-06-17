package com.nairusoft.bbre.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivitySplashBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Splash Screen Activity - First screen shown when app launches
 * Displays BBre and NairuSoft logos with security initialization
 */
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    private val splashTimeOut: Long = 3000 // 3 seconds

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Hide action bar
        supportActionBar?.hide()

        // Initialize UI
        setupUI()
        
        // Navigate to login after delay
        navigateToLogin()
    }

    private fun setupUI() {
        // Set animation or transitions here
        binding.tvBBre.alpha = 0f
        binding.tvNairuSoft.alpha = 0f
        binding.tvDescription.alpha = 0f

        // Fade in animation
        binding.tvBBre.animate()
            .alpha(1f)
            .setDuration(800)
            .start()

        binding.tvNairuSoft.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(400)
            .start()

        binding.tvDescription.animate()
            .alpha(1f)
            .setDuration(800)
            .setStartDelay(800)
            .start()
    }

    private fun navigateToLogin() {
        Handler(Looper.getMainLooper()).postDelayed({
            lifecycleScope.launch {
                // Simulate security initialization
                delay(splashTimeOut)
                
                // Navigate to Login Activity
                startActivity(android.content.Intent(this@SplashActivity, LoginActivity::class.java))
                finish()
                overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            }
        }, splashTimeOut)
    }

    override fun onBackPressed() {
        // Prevent back navigation from splash screen
        // Do nothing or show exit confirmation
        super.onBackPressed()
    }
}
