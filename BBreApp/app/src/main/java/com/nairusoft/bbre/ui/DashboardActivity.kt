package com.nairusoft.bbre.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding
import com.nairusoft.bbre.databinding.DialogKeyBinding
import com.nairusoft.bbre.security.AuthValidator
import com.nairusoft.bbre.security.KeyUtils
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.SessionManager
import com.nairusoft.bbre.security.StorageLoadResult
import com.nairusoft.bbre.security.StorageManager
import com.nairusoft.bbre.security.StorageOpResult
import com.nairusoft.bbre.security.UserKey
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var securityManager: SecurityManager
    private lateinit var storageManager: StorageManager
    private val sessionManager: SessionManager = SessionManager.getInstance()
    private lateinit var keysAdapter: KeysAdapter
    private var currentUser: String = ""
    private var currentUserKeys: List<UserKey> = emptyList()
    private var isRedirectingToLogin = false
    private var isKeysTabSelected = true
    private var isUpdatingBottomNav = false
    private var activeDialog: AlertDialog? = null

    companion object {
        private const val STATE_ACTIVE_TAB_KEYS = "state_active_tab_keys"
    }

    private val logoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logoutRunnable = Runnable {
        autoLogout()
    }
    private val clipboardHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var lastCopiedKey: String? = null
    private val clearClipboardRunnable = Runnable {
        clearCopiedClipboardIfUnchanged()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (isRedirectingToLogin) return
        sessionManager.recordActivity()
        scheduleRemainingTimeout()
    }

    override fun onStart() {
        super.onStart()
        if (!ensureValidSessionOrLogout()) return
    }

    override fun onResume() {
        super.onResume()
        if (!ensureValidSessionOrLogout()) return
        scheduleRemainingTimeout()
    }

    override fun onPause() {
        super.onPause()
        // Keep lastActivityElapsedMs in SessionManager; only stop foreground handler
        logoutHandler.removeCallbacks(logoutRunnable)
    }

    override fun onDestroy() {
        logoutHandler.removeCallbacks(logoutRunnable)
        clipboardHandler.removeCallbacks(clearClipboardRunnable)
        if (isFinishing && !isChangingConfigurations) {
            clearInMemoryAndStoredSession()
        }
        super.onDestroy()
    }

    private fun scheduleRemainingTimeout() {
        logoutHandler.removeCallbacks(logoutRunnable)
        val remaining = sessionManager.remainingTimeoutMs()
        if (remaining <= 0L) {
            autoLogout()
        } else {
            logoutHandler.postDelayed(logoutRunnable, remaining)
        }
    }

    private fun ensureValidSessionOrLogout(): Boolean {
        if (isRedirectingToLogin) return false
        val storedUser = securityManager.getSecureData(SessionManager.KEY_SESSION_USERNAME)
        val active = sessionManager.isSessionActive()
        val matchesStored = storedUser.isNotEmpty() &&
            sessionManager.activeUsername.equals(storedUser, ignoreCase = true)
        if (!active || !matchesStored) {
            autoLogout()
            return false
        }
        return true
    }

    private fun autoLogout() {
        if (isRedirectingToLogin) return
        Toast.makeText(this, getString(R.string.session_expired_inactivity), Toast.LENGTH_LONG).show()
        performManualLogout()
    }

    private fun clearInMemoryAndStoredSession() {
        logoutHandler.removeCallbacks(logoutRunnable)
        activeDialog?.dismiss()
        activeDialog = null
        sessionManager.clearSession()
        securityManager.removeSecureData(SessionManager.KEY_SESSION_USERNAME)
        currentUser = ""
        currentUserKeys = emptyList()
        if (::keysAdapter.isInitialized) {
            keysAdapter.updateData(emptyList())
        }
        if (::binding.isInitialized) {
            binding.etSettingsUsername.text?.clear()
            binding.etSettingsPassword.text?.clear()
        }
    }

    private fun performManualLogout() {
        if (isRedirectingToLogin) return
        isRedirectingToLogin = true
        clearInMemoryAndStoredSession()
        navigateToLogin()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        securityManager = SecurityManager.getInstance(this)
        storageManager = StorageManager.getInstance(this)

        val storedUser = securityManager.getSecureData(SessionManager.KEY_SESSION_USERNAME)
        if (storedUser.isEmpty() ||
            !sessionManager.isSessionActive() ||
            !sessionManager.activeUsername.equals(storedUser, ignoreCase = true)
        ) {
            performManualLogout()
            return
        }
        currentUser = storedUser

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBottomNavigation()
        setupKeysTab()
        setupSettingsTab()

        val restoreKeysTab = savedInstanceState?.getBoolean(STATE_ACTIVE_TAB_KEYS, true) ?: true
        switchTab(restoreKeysTab)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ACTIVE_TAB_KEYS, isKeysTabSelected)
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = getString(R.string.nav_my_keys)
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setupBottomNavigation() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (isUpdatingBottomNav) return@setOnItemSelectedListener true
            when (item.itemId) {
                R.id.nav_keys -> {
                    switchTab(true)
                    true
                }
                R.id.nav_settings -> {
                    switchTab(false)
                    true
                }
                else -> false
            }
        }
    }

    private fun switchTab(showKeys: Boolean) {
        isKeysTabSelected = showKeys
        val targetItemId = if (showKeys) R.id.nav_keys else R.id.nav_settings
        if (binding.bottomNav.selectedItemId != targetItemId) {
            isUpdatingBottomNav = true
            binding.bottomNav.selectedItemId = targetItemId
            isUpdatingBottomNav = false
        }

        if (showKeys) {
            binding.layoutKeys.visibility = View.VISIBLE
            binding.layoutSettings.visibility = View.GONE
            supportActionBar?.title = getString(R.string.nav_my_keys)
            loadKeysList()
        } else {
            binding.layoutKeys.visibility = View.GONE
            binding.layoutSettings.visibility = View.VISIBLE
            supportActionBar?.title = getString(R.string.nav_settings)

            // Reload user info into settings fields
            binding.etSettingsUsername.setText(currentUser)
            binding.etSettingsPassword.text?.clear()
            binding.tilSettingsUsername.error = null
            binding.tilSettingsPassword.error = null
        }
    }

    // --- Date Formatting Helper ---

    private fun getCurrentFormattedDate(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        return sdf.format(Date())
    }

    // --- Keys Tab Logic ---

    private fun setupKeysTab() {
        keysAdapter = KeysAdapter(
            emptyList(),
            onRowClick = { userKey -> copyToClipboard(userKey.value) },
            onInfoClick = { userKey -> showKeyInfoDialog(userKey) },
            onEditClick = { userKey -> showKeyDialog(userKey) },
            onDeleteClick = { userKey -> showDeleteKeyConfirmation(userKey) }
        )

        binding.rvKeys.layoutManager = LinearLayoutManager(this)
        binding.rvKeys.adapter = keysAdapter

        binding.fabAddKey.setOnClickListener {
            showKeyDialog(null)
        }

        loadKeysList()
    }

    private fun loadKeysList() {
        lifecycleScope.launch {
            when (val result = storageManager.getKeysForUserResult(currentUser)) {
                is StorageLoadResult.Success -> {
                    val keys = result.data
                    currentUserKeys = keys
                    if (keys.isEmpty()) {
                        binding.llEmptyState.visibility = View.VISIBLE
                        binding.rvKeys.visibility = View.GONE
                        keysAdapter.updateData(emptyList())
                    } else {
                        binding.llEmptyState.visibility = View.GONE
                        binding.rvKeys.visibility = View.VISIBLE
                        keysAdapter.updateData(keys)
                    }
                }
                is StorageLoadResult.Error -> {
                    Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.clipboard_label), text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        lastCopiedKey = text
        clipboardHandler.removeCallbacks(clearClipboardRunnable)
        clipboardHandler.postDelayed(clearClipboardRunnable, 45_000L)
        Toast.makeText(this, getString(R.string.clipboard_copied_msg), Toast.LENGTH_SHORT).show()
    }

    private fun clearCopiedClipboardIfUnchanged() {
        val expected = lastCopiedKey ?: return
        lastCopiedKey = null
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val currentText = clipboard.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.text?.toString()
            if (currentText == expected) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    clipboard.clearPrimaryClip()
                } else {
                    clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
                }
            }
        } catch (_: Exception) {
            // Clipboard access may be restricted while app is in background on Android 10+
        }
    }

    private fun showKeyInfoDialog(key: UserKey) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_key_info_title))
            .setMessage(
                getString(
                    R.string.dialog_key_info_body,
                    key.createdBy.ifEmpty { currentUser },
                    key.type,
                    key.description.ifEmpty { getString(R.string.value_no_description) },
                    key.value,
                    key.creationDate.ifEmpty { getString(R.string.value_unknown) },
                    key.modificationDate.ifEmpty { getString(R.string.value_no_modifications) },
                    key.version
                )
            )
            .setPositiveButton(getString(R.string.btn_copy_key)) { _, _ -> copyToClipboard(key.value) }
            .setNegativeButton(getString(R.string.btn_close), null)
            .show()
    }

    private fun showKeyDialog(existingKey: UserKey?) {
        activeDialog?.dismiss()
        val dialogBinding = DialogKeyBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        activeDialog = dialog

        dialogBinding.tvDialogTitle.text = if (existingKey == null) {
            getString(R.string.dialog_add_key_title)
        } else {
            getString(R.string.dialog_edit_key_title)
        }

        // Merge base banks with custom banks already saved by the user (sorted, no duplicates)
        val customBankOption = getString(R.string.option_add_new_bank)
        val bankOptions = KeyUtils.buildBankOptions(currentUserKeys, customBankOption)
        val bankAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, bankOptions)
        dialogBinding.actvKeyType.setAdapter(bankAdapter)

        var selectedBank = bankOptions.first()
        dialogBinding.actvKeyType.setText(selectedBank, false)

        // Clear validation errors automatically as the user types or changes bank
        dialogBinding.etCustomBank.doAfterTextChanged { dialogBinding.tilCustomBank.error = null }
        dialogBinding.etDescription.doAfterTextChanged { dialogBinding.tilDescription.error = null }
        dialogBinding.etKeyValue.doAfterTextChanged { dialogBinding.tilKeyValue.error = null }

        dialogBinding.actvKeyType.setOnItemClickListener { _, _, position, _ ->
            selectedBank = bankOptions[position]
            dialogBinding.tilKeyType.error = null
            dialogBinding.tilCustomBank.error = null
            if (selectedBank == customBankOption) {
                dialogBinding.tilCustomBank.visibility = View.VISIBLE
            } else {
                dialogBinding.tilCustomBank.visibility = View.GONE
            }
        }

        // Pre-populate if editing
        if (existingKey != null) {
            val matchingBank = bankOptions.firstOrNull { it.equals(existingKey.type.trim(), ignoreCase = true) }
            if (matchingBank != null) {
                selectedBank = matchingBank
                dialogBinding.actvKeyType.setText(matchingBank, false)
                dialogBinding.tilCustomBank.visibility = View.GONE
            } else {
                selectedBank = customBankOption
                dialogBinding.actvKeyType.setText(customBankOption, false)
                dialogBinding.tilCustomBank.visibility = View.VISIBLE
                dialogBinding.etCustomBank.setText(existingKey.type)
            }
            dialogBinding.etDescription.setText(existingKey.description)
            dialogBinding.etKeyValue.setText(existingKey.value)
        }

        dialogBinding.btnGenerateKey.setOnClickListener {
            sessionManager.recordActivity()
            scheduleRemainingTimeout()
            val generatedCode = KeyUtils.generateUniqueAlphanumericCode(
                currentUserKeys.map { it.value }
            )
            dialogBinding.etKeyValue.setText(generatedCode)
            dialogBinding.etKeyValue.setSelection(generatedCode.length)
            dialogBinding.tilKeyValue.error = null
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            sessionManager.recordActivity()
            scheduleRemainingTimeout()

            // Clear previous errors before validating
            dialogBinding.tilKeyType.error = null
            dialogBinding.tilCustomBank.error = null
            dialogBinding.tilDescription.error = null
            dialogBinding.tilKeyValue.error = null

            var hasError = false
            var type = KeyUtils.normalizeField(dialogBinding.actvKeyType.text.toString())
            if (type.isEmpty()) {
                dialogBinding.tilKeyType.error = getString(R.string.error_select_bank)
                hasError = true
            } else if (type == customBankOption) {
                val customBank = KeyUtils.normalizeField(dialogBinding.etCustomBank.text.toString())
                if (customBank.isEmpty()) {
                    dialogBinding.tilCustomBank.error = getString(R.string.error_custom_bank_empty)
                    hasError = true
                } else {
                    type = customBank
                }
            }

            val description = KeyUtils.normalizeField(dialogBinding.etDescription.text.toString())
            val value = KeyUtils.normalizeKeyValue(dialogBinding.etKeyValue.text.toString())

            if (description.isEmpty()) {
                dialogBinding.tilDescription.error = getString(R.string.error_description_empty)
                hasError = true
            }

            if (value.isEmpty()) {
                dialogBinding.tilKeyValue.error = getString(R.string.error_key_value_empty)
                hasError = true
            }

            if (hasError) return@setOnClickListener

            val currentTime = getCurrentFormattedDate()

            lifecycleScope.launch {
                if (existingKey == null) {
                    // Add key
                    val newKey = UserKey(
                        id = UUID.randomUUID().toString(),
                        type = type,
                        value = value,
                        username = currentUser,
                        createdBy = currentUser,
                        creationDate = currentTime,
                        modificationDate = currentTime,
                        version = "0.0.1",
                        description = description
                    )
                    when (val result = storageManager.addKey(newKey)) {
                        is StorageOpResult.Success -> {
                            Toast.makeText(this@DashboardActivity, getString(R.string.msg_key_added), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            loadKeysList()
                        }
                        is StorageOpResult.Duplicate -> {
                            dialogBinding.tilKeyValue.error = getString(R.string.error_duplicate_key)
                        }
                        is StorageOpResult.Error -> {
                            Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                        is StorageOpResult.NotFound -> Unit
                    }
                } else {
                    // Update key & bump patch version
                    val updatedKey = existingKey.copy(
                        type = type,
                        value = value,
                        description = description,
                        modificationDate = currentTime,
                        version = KeyUtils.incrementPatchVersion(existingKey.version)
                    )
                    when (val result = storageManager.updateKey(updatedKey)) {
                        is StorageOpResult.Success -> {
                            Toast.makeText(this@DashboardActivity, getString(R.string.msg_key_updated), Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            loadKeysList()
                        }
                        is StorageOpResult.Duplicate -> {
                            dialogBinding.tilKeyValue.error = getString(R.string.error_duplicate_key)
                        }
                        is StorageOpResult.Error -> {
                            Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                        is StorageOpResult.NotFound -> {
                            Toast.makeText(this@DashboardActivity, getString(R.string.error_key_not_found), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showDeleteKeyConfirmation(userKey: UserKey) {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_delete_key_title))
            .setMessage(getString(R.string.dialog_delete_key_message))
            .setPositiveButton(getString(R.string.btn_delete)) { _, _ ->
                lifecycleScope.launch {
                    when (val result = storageManager.deleteKey(userKey.id)) {
                        is StorageOpResult.Success -> {
                            Toast.makeText(this@DashboardActivity, getString(R.string.msg_key_deleted), Toast.LENGTH_SHORT).show()
                            loadKeysList()
                        }
                        is StorageOpResult.Error -> {
                            Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                        is StorageOpResult.NotFound, is StorageOpResult.Duplicate -> Unit
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }

    // --- Settings Tab Logic ---

    private fun setupSettingsTab() {
        binding.etSettingsUsername.doAfterTextChanged { binding.tilSettingsUsername.error = null }
        binding.etSettingsPassword.doAfterTextChanged { binding.tilSettingsPassword.error = null }

        binding.btnSaveProfile.setOnClickListener {
            val newUsername = binding.etSettingsUsername.text.toString().trim()
            val newPassword = binding.etSettingsPassword.text?.toString() ?: ""

            when (AuthValidator.validateUsername(newUsername)) {
                AuthValidator.UsernameError.EMPTY -> {
                    binding.tilSettingsUsername.error = getString(R.string.error_settings_username_empty)
                    return@setOnClickListener
                }
                AuthValidator.UsernameError.CONTAINS_WHITESPACE -> {
                    binding.tilSettingsUsername.error = getString(R.string.error_settings_username_spaces)
                    return@setOnClickListener
                }
                null -> binding.tilSettingsUsername.error = null
            }

            if (newPassword.isNotEmpty()) {
                when (AuthValidator.validatePassword(newPassword)) {
                    AuthValidator.PasswordError.CONTAINS_WHITESPACE -> {
                        binding.tilSettingsPassword.error = getString(R.string.error_password_spaces)
                        return@setOnClickListener
                    }
                    AuthValidator.PasswordError.TOO_SHORT -> {
                        binding.tilSettingsPassword.error = getString(R.string.error_new_password_min_length)
                        return@setOnClickListener
                    }
                    AuthValidator.PasswordError.EMPTY, null -> Unit
                }
            }
            binding.tilSettingsPassword.error = null

            lifecycleScope.launch {
                val users = when (val load = storageManager.getUsersResult()) {
                    is StorageLoadResult.Success -> load.data
                    is StorageLoadResult.Error -> {
                        Toast.makeText(this@DashboardActivity, load.message, Toast.LENGTH_LONG).show()
                        return@launch
                    }
                }
                val foundUser = users.find { it.username.equals(currentUser, ignoreCase = true) }
                if (foundUser == null) {
                    Toast.makeText(
                        this@DashboardActivity,
                        getString(R.string.error_current_user_missing),
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                if (newUsername == foundUser.username && newPassword.isEmpty()) {
                    Toast.makeText(this@DashboardActivity, getString(R.string.msg_no_changes), Toast.LENGTH_SHORT).show()
                    return@launch
                }

                val applyProfileUpdate: () -> Unit = {
                    lifecycleScope.launch {
                        val updatedUser = if (newPassword.isNotEmpty()) {
                            securityManager.createUserCredentials(newUsername, newPassword)
                        } else {
                            // Keep historical createdBy intact on existing keys (D-04)
                            foundUser.copy(username = newUsername)
                        }
                        when (val updateResult = storageManager.updateUser(currentUser, updatedUser)) {
                            is StorageOpResult.Success -> {
                                val usernameChanged = !currentUser.equals(newUsername, ignoreCase = true)
                                currentUser = newUsername
                                sessionManager.updateActiveUsername(newUsername)
                                securityManager.storeSecureData(SessionManager.KEY_SESSION_USERNAME, newUsername)
                                securityManager.storeSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER, newUsername)
                                if (usernameChanged) {
                                    securityManager.clearBiometricEnrollment()
                                }
                                binding.etSettingsPassword.text?.clear()
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.msg_profile_updated),
                                    Toast.LENGTH_SHORT
                                ).show()
                                switchTab(true)
                            }
                            is StorageOpResult.Duplicate -> {
                                binding.tilSettingsUsername.error = getString(R.string.error_settings_username_taken)
                            }
                            is StorageOpResult.NotFound -> {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.error_user_update_not_found),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is StorageOpResult.Error -> {
                                Toast.makeText(this@DashboardActivity, updateResult.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                }

                if (newPassword.isNotEmpty()) {
                    requestReauthentication(
                        title = getString(R.string.dialog_reauth_title),
                        message = getString(R.string.dialog_reauth_password_change_msg),
                        onAuthenticated = applyProfileUpdate
                    )
                } else {
                    applyProfileUpdate()
                }
            }
        }

        binding.btnSystemInfo.setOnClickListener {
            showSystemInfoDialog()
        }

        binding.btnLogout.setOnClickListener {
            performManualLogout()
        }

        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountConfirmation()
        }
    }

    private fun showSystemInfoDialog() {
        activeDialog?.dismiss()
        val latestKey = currentUserKeys.maxByOrNull { it.modificationDate.ifEmpty { it.creationDate } }
        val keysSummary = if (latestKey != null) {
            getString(
                R.string.dialog_system_info_latest_key,
                latestKey.type,
                latestKey.createdBy.ifEmpty { currentUser },
                latestKey.creationDate.ifEmpty { getString(R.string.value_unknown) },
                latestKey.modificationDate.ifEmpty { getString(R.string.value_no_modifications) },
                latestKey.version
            )
        } else {
            getString(R.string.dialog_system_info_no_keys)
        }

        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_system_info_title))
            .setMessage(
                getString(
                    R.string.dialog_system_info_base,
                    currentUser,
                    currentUserKeys.size,
                    "0.0.1"
                ) + keysSummary
            )
            .setPositiveButton(getString(R.string.btn_accept), null)
            .show()
    }

    private fun requestReauthentication(
        title: String,
        message: String,
        onAuthenticated: () -> Unit
    ) {
        activeDialog?.dismiss()

        val density = resources.displayMetrics.density
        val paddingPx = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingPx, (8 * density).toInt(), paddingPx, 0)
        }

        val tvMessage = TextView(this).apply {
            text = message
            setPadding(0, 0, 0, (12 * density).toInt())
        }
        container.addView(tvMessage)

        val tilCurrentPassword = TextInputLayout(
            this,
            null,
            com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = getString(R.string.hint_current_password)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            errorIconDrawable = null
        }
        val etCurrentPassword = TextInputEditText(tilCurrentPassword.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            doAfterTextChanged { tilCurrentPassword.error = null }
        }
        tilCurrentPassword.addView(etCurrentPassword)
        container.addView(tilCurrentPassword)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(getString(R.string.btn_confirm), null)
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .create()

        activeDialog = dialog
        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val enteredPassword = etCurrentPassword.text?.toString() ?: ""
                if (enteredPassword.isEmpty()) {
                    tilCurrentPassword.error = getString(R.string.error_current_password_empty)
                    return@setOnClickListener
                }
                tilCurrentPassword.error = null
                positiveBtn.isEnabled = false

                lifecycleScope.launch {
                    val loadResult = storageManager.getUsersResult()
                    positiveBtn.isEnabled = true
                    when (loadResult) {
                        is StorageLoadResult.Error -> {
                            Toast.makeText(this@DashboardActivity, loadResult.message, Toast.LENGTH_LONG).show()
                        }
                        is StorageLoadResult.Success -> {
                            val user = loadResult.data.find {
                                it.username.equals(currentUser, ignoreCase = true)
                            }
                            if (user != null && securityManager.verifyUserPassword(user, enteredPassword)) {
                                dialog.dismiss()
                                onAuthenticated()
                            } else {
                                tilCurrentPassword.error = getString(R.string.error_current_password_wrong)
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteAccountConfirmation() {
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_delete_account_title))
            .setMessage(getString(R.string.dialog_delete_account_message, currentUser))
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                requestReauthentication(
                    title = getString(R.string.dialog_delete_account_reauth_title),
                    message = getString(R.string.dialog_delete_account_reauth_message)
                ) {
                    lifecycleScope.launch {
                        when (val result = storageManager.deleteUser(currentUser)) {
                            is StorageOpResult.Success -> {
                                securityManager.clearBiometricEnrollment()
                                securityManager.removeSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.msg_account_deleted),
                                    Toast.LENGTH_LONG
                                ).show()
                                performManualLogout()
                            }
                            is StorageOpResult.Error -> {
                                Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                            }
                            is StorageOpResult.NotFound -> {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.error_account_not_found),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is StorageOpResult.Duplicate -> Unit
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
