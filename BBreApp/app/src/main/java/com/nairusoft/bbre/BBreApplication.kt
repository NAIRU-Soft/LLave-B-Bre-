package com.nairusoft.bbre

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Application class for BBre - Secure Payment System
 * Initializes security components and app-wide configurations
 */
class BBreApplication : Application() {

    companion object {
        const val CHANNEL_ID = "bbre_security_channel"
        const val PREF_NAME = "bbre_secure_prefs"
        
        @Volatile
        private var instance: BBreApplication? = null
        
        fun getInstance(): BBreApplication {
            return instance ?: throw IllegalStateException("Application not initialized")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        initializeSecurity()
        createNotificationChannel()
        generateMasterKey()
    }

    /**
     * Initialize security components
     */
    private fun initializeSecurity() {
        // Verify keystore is available
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            println("BBre: Keystore initialized successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Create notification channel for security alerts
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Seguridad BBre"
            val descriptionText = "Notificaciones de seguridad y transacciones"
            val importance = NotificationManager.IMPORTANCE_HIGH
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
                enableLights(true)
                enableVibration(true)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Generate or retrieve master key for encryption
     */
    private fun generateMasterKey() {
        try {
            val masterKey = MasterKey.Builder(this)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            
            // Create encrypted shared preferences
            EncryptedSharedPreferences.create(
                this,
                PREF_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            println("BBre: EncryptedSharedPreferences created successfully")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Generate a secure key in Android Keystore
     */
    fun generateSecureKey(alias: String): SecretKey? {
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                "AndroidKeyStore"
            )
            
            val keyGenParameterSpec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true) // Requires biometric/PIN
                .build()
            
            keyGenerator.init(keyGenParameterSpec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
