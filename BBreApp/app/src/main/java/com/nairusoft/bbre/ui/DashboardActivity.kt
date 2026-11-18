package com.nairusoft.bbre.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nairusoft.bbre.BuildConfig
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding
import com.nairusoft.bbre.databinding.DialogBackupExportBinding
import com.nairusoft.bbre.databinding.DialogBackupImportBinding
import com.nairusoft.bbre.databinding.DialogKeyBinding
import com.nairusoft.bbre.model.AccountDeleteOutcome
import com.nairusoft.bbre.model.ProfileUpdateOutcome
import com.nairusoft.bbre.model.ReauthOutcome
import com.nairusoft.bbre.model.RestoreAccountResult
import com.nairusoft.bbre.model.StorageOpResult
import com.nairusoft.bbre.model.UserKey
import com.nairusoft.bbre.security.AuthValidator
import com.nairusoft.bbre.security.BackupCryptoManager
import com.nairusoft.bbre.security.KeyUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Arrays
import java.util.Date
import java.util.Locale

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private val viewModel: DashboardViewModel by viewModels { DashboardViewModel.factory(this) }
    private lateinit var keysAdapter: KeysAdapter
    private var isRedirectingToLogin = false
    private var isKeysTabSelected = true
    private var isUpdatingBottomNav = false
    private var activeDialog: AlertDialog? = null
    private var pendingExportPassword: CharArray? = null

    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            handleExportUriSelected(uri)
        }

    private val importBackupLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            handleImportUriSelected(uri)
        }

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
        val remaining = viewModel.recordUserActivity()
        scheduleRemainingTimeout(remaining)
    }

    override fun onStart() {
        super.onStart()
        if (!ensureValidSessionOrLogout()) return
    }

    override fun onResume() {
        super.onResume()
        if (!ensureValidSessionOrLogout()) return
        scheduleRemainingTimeout(viewModel.remainingTimeoutMs())
    }

    override fun onPause() {
        super.onPause()
        // Keep lastActivityElapsedMs in SessionManager; only stop foreground handler
        logoutHandler.removeCallbacks(logoutRunnable)
    }

    override fun onDestroy() {
        logoutHandler.removeCallbacks(logoutRunnable)
        clipboardHandler.removeCallbacks(clearClipboardRunnable)
        activeDialog?.dismiss()
        activeDialog = null
        if (isFinishing && !isChangingConfigurations) {
            clearInMemoryAndStoredSession()
        }
        super.onDestroy()
    }

    private fun scheduleRemainingTimeout(remaining: Long = viewModel.remainingTimeoutMs()) {
        logoutHandler.removeCallbacks(logoutRunnable)
        if (remaining <= 0L) {
            autoLogout()
        } else {
            logoutHandler.postDelayed(logoutRunnable, remaining)
        }
    }

    private fun ensureValidSessionOrLogout(): Boolean {
        if (isRedirectingToLogin) return false
        if (!viewModel.isSessionValid()) {
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

    private fun clearPendingExportPassword() {
        pendingExportPassword?.let { Arrays.fill(it, '\u0000') }
        pendingExportPassword = null
    }

    private fun clearInMemoryAndStoredSession() {
        logoutHandler.removeCallbacks(logoutRunnable)
        clearPendingExportPassword()
        activeDialog?.dismiss()
        activeDialog = null
        viewModel.clearSessionAndInMemoryData()
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

        if (viewModel.initializeActiveSession() == null) {
            performManualLogout()
            return
        }

        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        setupBottomNavigation()
        setupKeysTab()
        setupSettingsTab()
        observeViewModel()

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

    private fun observeViewModel() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.keys.collect { keys ->
                        if (keys.isEmpty()) {
                            binding.llEmptyState.visibility = View.VISIBLE
                            binding.rvKeys.visibility = View.GONE
                        } else {
                            binding.llEmptyState.visibility = View.GONE
                            binding.rvKeys.visibility = View.VISIBLE
                        }
                        keysAdapter.updateData(keys)
                    }
                }
                launch {
                    viewModel.errorMessages.collect { message ->
                        Toast.makeText(this@DashboardActivity, message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
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
            viewModel.loadKeys()
        } else {
            binding.layoutKeys.visibility = View.GONE
            binding.layoutSettings.visibility = View.VISIBLE
            supportActionBar?.title = getString(R.string.nav_settings)

            // Reload user info into settings fields
            binding.etSettingsUsername.setText(viewModel.currentUser.value)
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

        viewModel.loadKeys()
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
                    key.createdBy.ifEmpty { viewModel.currentUser.value },
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
        val bankOptions = viewModel.getBankOptions(customBankOption)
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
            val remaining = viewModel.recordUserActivity()
            scheduleRemainingTimeout(remaining)
            val generatedCode = viewModel.generateUniqueCode()
            dialogBinding.etKeyValue.setText(generatedCode)
            dialogBinding.etKeyValue.setSelection(generatedCode.length)
            dialogBinding.tilKeyValue.error = null
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val remaining = viewModel.recordUserActivity()
            scheduleRemainingTimeout(remaining)

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

            dialogBinding.btnSave.isEnabled = false
            viewModel.saveKey(
                existingKey = existingKey,
                type = type,
                value = value,
                description = description,
                timestamp = getCurrentFormattedDate()
            ) { result ->
                if (isFinishing || isDestroyed) return@saveKey
                dialogBinding.btnSave.isEnabled = true
                when (result) {
                    is StorageOpResult.Success -> {
                        val msgRes = if (existingKey == null) R.string.msg_key_added else R.string.msg_key_updated
                        Toast.makeText(this@DashboardActivity, getString(msgRes), Toast.LENGTH_SHORT).show()
                        dialog.dismiss()
                    }
                    is StorageOpResult.Duplicate -> {
                        dialogBinding.tilKeyValue.error = getString(R.string.error_duplicate_key)
                    }
                    is StorageOpResult.Error -> {
                        Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                    }
                    is StorageOpResult.NotFound -> {
                        if (existingKey != null) {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.error_key_not_found),
                                Toast.LENGTH_SHORT
                            ).show()
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
                viewModel.deleteKey(userKey.id) { result ->
                    if (isFinishing || isDestroyed) return@deleteKey
                    when (result) {
                        is StorageOpResult.Success -> {
                            Toast.makeText(this@DashboardActivity, getString(R.string.msg_key_deleted), Toast.LENGTH_SHORT).show()
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

            val applyProfileUpdate: () -> Unit = {
                viewModel.updateProfile(newUsername, newPassword) { outcome ->
                    if (isFinishing || isDestroyed) return@updateProfile
                    when (outcome) {
                        is ProfileUpdateOutcome.Updated -> {
                            binding.etSettingsPassword.text?.clear()
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.msg_profile_updated),
                                Toast.LENGTH_SHORT
                            ).show()
                            switchTab(true)
                        }
                        is ProfileUpdateOutcome.NoChanges -> {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.msg_no_changes),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        is ProfileUpdateOutcome.UserMissing -> {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.error_current_user_missing),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is ProfileUpdateOutcome.DuplicateUsername -> {
                            binding.tilSettingsUsername.error = getString(R.string.error_settings_username_taken)
                        }
                        is ProfileUpdateOutcome.UpdateNotFound -> {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.error_user_update_not_found),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is ProfileUpdateOutcome.Error -> {
                            Toast.makeText(this@DashboardActivity, outcome.message, Toast.LENGTH_LONG).show()
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

        binding.btnExportBackup.setOnClickListener {
            showExportBackupDialog()
        }

        binding.btnImportBackup.setOnClickListener {
            if (!ensureValidSessionOrLogout()) return@setOnClickListener
            val remaining = viewModel.recordUserActivity()
            scheduleRemainingTimeout(remaining)
            importBackupLauncher.launch(arrayOf("application/octet-stream", "application/json", "*/*"))
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

    // --- Encrypted Backup (AES-256-GCM) Flows ---

    private fun showExportBackupDialog() {
        activeDialog?.dismiss()
        val dialogBinding = DialogBackupExportBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        activeDialog = dialog

        dialogBinding.etExportCurrentPassword.doAfterTextChanged {
            dialogBinding.tilExportCurrentPassword.error = null
        }
        dialogBinding.etExportBackupPassword.doAfterTextChanged {
            dialogBinding.tilExportBackupPassword.error = null
        }
        dialogBinding.etExportBackupConfirm.doAfterTextChanged {
            dialogBinding.tilExportBackupConfirm.error = null
        }

        dialogBinding.btnCancelExport.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmExport.setOnClickListener {
            val remaining = viewModel.recordUserActivity()
            scheduleRemainingTimeout(remaining)

            dialogBinding.tilExportCurrentPassword.error = null
            dialogBinding.tilExportBackupPassword.error = null
            dialogBinding.tilExportBackupConfirm.error = null

            val currentPassword = dialogBinding.etExportCurrentPassword.text?.toString() ?: ""
            val backupPassword = dialogBinding.etExportBackupPassword.text?.toString() ?: ""
            val confirmPassword = dialogBinding.etExportBackupConfirm.text?.toString() ?: ""

            var hasError = false
            if (currentPassword.isEmpty()) {
                dialogBinding.tilExportCurrentPassword.error = getString(R.string.error_current_password_empty)
                hasError = true
            }

            when (AuthValidator.validatePassword(backupPassword)) {
                AuthValidator.PasswordError.EMPTY -> {
                    dialogBinding.tilExportBackupPassword.error = getString(R.string.error_password_empty)
                    hasError = true
                }
                AuthValidator.PasswordError.CONTAINS_WHITESPACE -> {
                    dialogBinding.tilExportBackupPassword.error = getString(R.string.error_password_spaces)
                    hasError = true
                }
                AuthValidator.PasswordError.TOO_SHORT -> {
                    dialogBinding.tilExportBackupPassword.error = getString(R.string.error_password_min_length)
                    hasError = true
                }
                null -> Unit
            }

            if (confirmPassword != backupPassword) {
                dialogBinding.tilExportBackupConfirm.error = getString(R.string.error_backup_password_mismatch)
                hasError = true
            }

            if (hasError) return@setOnClickListener

            dialogBinding.btnConfirmExport.isEnabled = false
            viewModel.verifyCurrentPassword(currentPassword) { outcome ->
                if (isFinishing || isDestroyed) return@verifyCurrentPassword
                dialogBinding.btnConfirmExport.isEnabled = true
                when (outcome) {
                    is ReauthOutcome.Verified -> {
                        clearPendingExportPassword()
                        pendingExportPassword = backupPassword.toCharArray()
                        dialog.dismiss()

                        val safeUser = viewModel.currentUser.value
                            .replace(Regex("[^A-Za-z0-9_-]"), "_")
                            .ifEmpty { "usuario" }
                        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
                        exportBackupLauncher.launch("bbre_respaldo_${safeUser}_${stamp}.bbre")
                    }
                    is ReauthOutcome.WrongPassword -> {
                        dialogBinding.tilExportCurrentPassword.error = getString(R.string.error_current_password_wrong)
                    }
                    is ReauthOutcome.Error -> {
                        Toast.makeText(this@DashboardActivity, outcome.message, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        dialog.show()
    }

    private fun handleExportUriSelected(uri: Uri?) {
        val passphrase = pendingExportPassword
        pendingExportPassword = null

        if (uri == null || passphrase == null) {
            passphrase?.let { Arrays.fill(it, '\u0000') }
            return
        }
        if (!ensureValidSessionOrLogout()) {
            Arrays.fill(passphrase, '\u0000')
            return
        }

        val remaining = viewModel.recordUserActivity()
        scheduleRemainingTimeout(remaining)

        viewModel.exportEncryptedBackup(
            backupPassword = passphrase,
            appVersion = BuildConfig.VERSION_NAME,
            exportedAt = getCurrentFormattedDate()
        ) { result ->
            if (isFinishing || isDestroyed) return@exportEncryptedBackup
            result.fold(
                onSuccess = { bundle ->
                    lifecycleScope.launch {
                        val writeResult = withContext(Dispatchers.IO) {
                            runCatching {
                                contentResolver.openOutputStream(uri, "wt")?.use { output ->
                                    output.write(bundle.encryptedJson.toByteArray(StandardCharsets.UTF_8))
                                    output.flush()
                                } ?: throw IllegalStateException("No se pudo abrir el destino para escritura")
                            }
                        }
                        if (isFinishing || isDestroyed) return@launch
                        writeResult.fold(
                            onSuccess = {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.msg_backup_saved, bundle.keyCount),
                                    Toast.LENGTH_LONG
                                ).show()
                            },
                            onFailure = { error ->
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.error_backup_save_failed, error.localizedMessage ?: ""),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        )
                    }
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@DashboardActivity,
                        getString(R.string.error_backup_save_failed, error.localizedMessage ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun handleImportUriSelected(uri: Uri?) {
        if (uri == null || !ensureValidSessionOrLogout()) return
        val remaining = viewModel.recordUserActivity()
        scheduleRemainingTimeout(remaining)

        lifecycleScope.launch {
            val readOutcome = withContext(Dispatchers.IO) {
                readBoundedBackupFile(uri)
            }
            if (isFinishing || isDestroyed) return@launch
            readOutcome.fold(
                onSuccess = { envelopeJson ->
                    showImportBackupDialog(envelopeJson)
                },
                onFailure = { error ->
                    val messageRes = if (error is BackupFileTooLargeException) {
                        R.string.error_backup_file_too_large
                    } else {
                        R.string.error_backup_file_read
                    }
                    Toast.makeText(this@DashboardActivity, getString(messageRes), Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private class BackupFileTooLargeException : Exception()

    private fun readBoundedBackupFile(uri: Uri): Result<String> {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(8192)
                val out = ByteArrayOutputStream()
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    total += read
                    if (total > BackupCryptoManager.MAX_BACKUP_BYTES) {
                        throw BackupFileTooLargeException()
                    }
                    out.write(buffer, 0, read)
                }
                val text = String(out.toByteArray(), StandardCharsets.UTF_8)
                if (text.isBlank()) {
                    throw IllegalArgumentException("Archivo vacío")
                }
                text
            } ?: throw IllegalStateException("No se pudo abrir el archivo")
        }
    }

    private fun showImportBackupDialog(envelopeJson: String) {
        activeDialog?.dismiss()
        val dialogBinding = DialogBackupImportBinding.inflate(LayoutInflater.from(this))
        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()
        dialog.window?.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
        activeDialog = dialog

        dialogBinding.etImportBackupPassword.doAfterTextChanged {
            dialogBinding.tilImportBackupPassword.error = null
        }

        dialogBinding.btnCancelImport.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnConfirmImport.setOnClickListener {
            val remaining = viewModel.recordUserActivity()
            scheduleRemainingTimeout(remaining)

            val backupPassword = dialogBinding.etImportBackupPassword.text?.toString() ?: ""
            if (backupPassword.isEmpty()) {
                dialogBinding.tilImportBackupPassword.error = getString(R.string.error_password_empty)
                return@setOnClickListener
            }

            dialogBinding.tilImportBackupPassword.error = null
            dialogBinding.btnConfirmImport.isEnabled = false

            viewModel.decryptBackupPreview(
                envelopeJson = envelopeJson,
                backupPassword = backupPassword.toCharArray()
            ) { result ->
                if (isFinishing || isDestroyed) return@decryptBackupPreview
                dialogBinding.btnConfirmImport.isEnabled = true

                result.fold(
                    onSuccess = { preview ->
                        dialog.dismiss()
                        if (preview.isSameUser) {
                            applyImportedKeysToCurrentUser(preview.payload.keys)
                        } else {
                            showCrossUserImportDialog(preview)
                        }
                    },
                    onFailure = {
                        dialogBinding.tilImportBackupPassword.error =
                            getString(R.string.error_backup_decrypt_failed)
                    }
                )
            }
        }

        dialog.show()
    }

    private fun applyImportedKeysToCurrentUser(incomingKeys: List<UserKey>) {
        val activeUser = viewModel.currentUser.value
        viewModel.importBackupKeysIntoCurrentUser(incomingKeys) { importResult ->
            if (isFinishing || isDestroyed) return@importBackupKeysIntoCurrentUser
            importResult.fold(
                onSuccess = { summary ->
                    Toast.makeText(
                        this@DashboardActivity,
                        getString(
                            R.string.msg_backup_loaded_summary,
                            activeUser,
                            summary.importedCount,
                            summary.skippedDuplicates
                        ),
                        Toast.LENGTH_LONG
                    ).show()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this@DashboardActivity,
                        error.localizedMessage ?: getString(R.string.error_backup_decrypt_failed),
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun showCrossUserImportDialog(preview: DashboardViewModel.BackupDecryptionPreview) {
        val activeUser = viewModel.currentUser.value
        val backupUser = preview.payload.user.username
        val exportedDate = preview.payload.exportedAt.ifEmpty { getString(R.string.value_unknown) }

        activeDialog?.dismiss()
        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_backup_cross_user_title))
            .setMessage(
                getString(
                    R.string.dialog_backup_cross_user_message,
                    backupUser,
                    preview.payload.keys.size,
                    exportedDate,
                    activeUser
                )
            )
            .setPositiveButton(getString(R.string.btn_import_keys_here)) { _, _ ->
                applyImportedKeysToCurrentUser(preview.payload.keys)
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)

        if (!preview.backupUserAlreadyExistsLocally) {
            builder.setNeutralButton(getString(R.string.btn_restore_account_too, backupUser)) { _, _ ->
                viewModel.restoreBackedUpAccount(preview.payload.user, preview.payload.keys) { result ->
                    if (isFinishing || isDestroyed) return@restoreBackedUpAccount
                    when (result) {
                        is RestoreAccountResult.Restored -> {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(
                                    R.string.msg_account_restored,
                                    backupUser,
                                    result.summary.importedCount,
                                    result.summary.skippedDuplicates
                                ),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is RestoreAccountResult.UserAlreadyExists -> {
                            Toast.makeText(
                                this@DashboardActivity,
                                getString(R.string.error_restore_account_exists, backupUser),
                                Toast.LENGTH_LONG
                            ).show()
                        }
                        is RestoreAccountResult.Error -> {
                            Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }

        activeDialog = builder.show()
    }

    private fun showSystemInfoDialog() {
        activeDialog?.dismiss()
        val activeUser = viewModel.currentUser.value
        val currentUserKeys = viewModel.keys.value
        val latestKey = currentUserKeys.maxByOrNull { it.modificationDate.ifEmpty { it.creationDate } }
        val keysSummary = if (latestKey != null) {
            getString(
                R.string.dialog_system_info_latest_key,
                latestKey.type,
                latestKey.createdBy.ifEmpty { activeUser },
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
                    activeUser,
                    currentUserKeys.size,
                    BuildConfig.VERSION_NAME
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

                viewModel.verifyCurrentPassword(enteredPassword) { outcome ->
                    if (isFinishing || isDestroyed) return@verifyCurrentPassword
                    positiveBtn.isEnabled = true
                    when (outcome) {
                        is ReauthOutcome.Verified -> {
                            dialog.dismiss()
                            onAuthenticated()
                        }
                        is ReauthOutcome.WrongPassword -> {
                            tilCurrentPassword.error = getString(R.string.error_current_password_wrong)
                        }
                        is ReauthOutcome.Error -> {
                            Toast.makeText(this@DashboardActivity, outcome.message, Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun showDeleteAccountConfirmation() {
        val activeUser = viewModel.currentUser.value
        activeDialog?.dismiss()
        activeDialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.dialog_delete_account_title))
            .setMessage(getString(R.string.dialog_delete_account_message, activeUser))
            .setPositiveButton(getString(R.string.btn_continue)) { _, _ ->
                requestReauthentication(
                    title = getString(R.string.dialog_delete_account_reauth_title),
                    message = getString(R.string.dialog_delete_account_reauth_message)
                ) {
                    viewModel.deleteAccount { outcome ->
                        if (isFinishing || isDestroyed) return@deleteAccount
                        when (outcome) {
                            is AccountDeleteOutcome.Success -> {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.msg_account_deleted),
                                    Toast.LENGTH_LONG
                                ).show()
                                performManualLogout()
                            }
                            is AccountDeleteOutcome.Error -> {
                                Toast.makeText(this@DashboardActivity, outcome.message, Toast.LENGTH_LONG).show()
                            }
                            is AccountDeleteOutcome.NotFound -> {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    getString(R.string.error_account_not_found),
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                }
            }
            .setNegativeButton(getString(R.string.btn_cancel), null)
            .show()
    }
}
