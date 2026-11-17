package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
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

        // Prevent back navigation while splash is visible
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Intentionally ignore back press on splash screen
            }
        })

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
        lifecycleScope.launch {
            delay(splashTimeOut)

            startActivity(Intent(this@SplashActivity, LoginActivity::class.java))
            finish()
            applyFadeCloseTransition()
        }
    }
}
