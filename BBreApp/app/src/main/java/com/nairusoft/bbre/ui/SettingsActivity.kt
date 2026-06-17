package com.nairusoft.bbre.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivitySettingsBinding
import com.nairusoft.bbre.data.UserManager
import com.nairusoft.bbre.security.SecurityManager

/**
 * Settings Activity - Manage user profile and keys
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var userManager: UserManager
    private lateinit var securityManager: SecurityManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager.getInstance(this)
        securityManager = SecurityManager.getInstance(this)

        setupToolbar()
        setupProfileSection()
        setupKeysButton()
        setupLogoutButton()
        setupDeleteUserButton()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupProfileSection() {
        val currentUser = securityManager.getSecureData("current_user", "")
        binding.tvCurrentUsername.text = "Usuario actual: $currentUser"
        
        binding.btnChangeProfile.setOnClickListener {
            showProfileDialog(currentUser)
        }
    }

    private fun setupKeysButton() {
        binding.btnManageKeys.setOnClickListener {
            startActivity(Intent(this, MyKeysActivity::class.java))
        }
    }

    private fun setupLogoutButton() {
        binding.btnLogout.setOnClickListener {
            securityManager.clearSecureData("current_user")
            startActivity(Intent(this, LoginActivity::class.java))
            finishAffinity()
        }
    }

    private fun setupDeleteUserButton() {
        binding.btnDeleteUser.setOnClickListener {
            confirmDeleteUser()
        }
    }

    private fun showProfileDialog(currentUsername: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_change_profile, null)
        val usernameInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNewUsername)
        val passwordInput = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etNewPassword)
        
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_profile)
            .setView(dialogView)
            .setPositiveButton(R.string.key_save) { _, _ ->
                val newUsername = usernameInput.text.toString().trim()
                val newPassword = passwordInput.text.toString().trim()
                
                if (newUsername.isEmpty() && newPassword.isEmpty()) {
                    return@setPositiveButton
                }
                
                val finalUsername = if (newUsername.isEmpty()) currentUsername else newUsername
                
                val success = userManager.updateUserProfile(currentUsername, finalUsername, 
                    if (newPassword.isEmpty()) null else newPassword)
                
                if (success) {
                    securityManager.storeSecureData("current_user", finalUsername)
                    binding.tvCurrentUsername.text = "Usuario actual: $finalUsername"
                }
            }
            .setNegativeButton(R.string.key_cancel, null)
            .show()
    }

    private fun confirmDeleteUser() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete_user)
            .setPositiveButton(R.string.settings_delete_user) { _, _ ->
                val currentUser = securityManager.getSecureData("current_user", "")
                val success = userManager.deleteUser(currentUser)
                
                if (success) {
                    securityManager.clearSecureData("current_user")
                    startActivity(Intent(this, LoginActivity::class.java))
                    finishAffinity()
                }
            }
            .setNegativeButton(R.string.key_cancel, null)
            .show()
    }
}
