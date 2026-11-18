package com.nairusoft.bbre

import android.app.Application
import android.util.Log
import com.nairusoft.bbre.security.SecurityManager

/**
 * Application class for BBre.
 * Initializes the shared [SecurityManager] singleton and Keystore-backed storage on startup.
 */
class BBreApplication : Application() {

    companion object {
        private const val TAG = "BBreApplication"
    }

    override fun onCreate() {
        super.onCreate()
        initializeSecurity()
    }

    /**
     * Warm up [SecurityManager] (Android Keystore + EncryptedSharedPreferences recovery) on launch.
     */
    private fun initializeSecurity() {
        try {
            SecurityManager.getInstance(this)
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "SecurityManager initialized successfully")
            }
        } catch (e: Exception) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "Security initialization failed: ${e.javaClass.simpleName}", e)
            }
        }
    }
}
