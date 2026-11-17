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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding
import com.nairusoft.bbre.databinding.DialogKeyBinding
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
    private var activeDialog: AlertDialog? = null

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
        Toast.makeText(this, "Sesión cerrada por inactividad", Toast.LENGTH_LONG).show()
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
    }

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.title = "Mis Llaves"
    }

    private fun navigateToLogin() {
        val intent = Intent(this, LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun setupBottomNavigation() {
        binding.tabKeys.setOnClickListener {
            switchTab(true)
        }
        binding.tabSettings.setOnClickListener {
            switchTab(false)
        }
    }

    private fun switchTab(showKeys: Boolean) {
        if (showKeys) {
            binding.layoutKeys.visibility = View.VISIBLE
            binding.layoutSettings.visibility = View.GONE
            supportActionBar?.title = "Mis Llaves"

            binding.ivTabKeys.setColorFilter(getColor(R.color.bbre_primary))
            binding.tvTabKeys.setTextColor(getColor(R.color.bbre_primary))
            binding.tvTabKeys.setTypeface(null, android.graphics.Typeface.BOLD)

            binding.ivTabSettings.setColorFilter(getColor(R.color.gray))
            binding.tvTabSettings.setTextColor(getColor(R.color.gray))
            binding.tvTabSettings.setTypeface(null, android.graphics.Typeface.NORMAL)
            
            loadKeysList()
        } else {
            binding.layoutKeys.visibility = View.GONE
            binding.layoutSettings.visibility = View.VISIBLE
            supportActionBar?.title = "Configuración"

            binding.ivTabKeys.setColorFilter(getColor(R.color.gray))
            binding.tvTabKeys.setTextColor(getColor(R.color.gray))
            binding.tvTabKeys.setTypeface(null, android.graphics.Typeface.NORMAL)

            binding.ivTabSettings.setColorFilter(getColor(R.color.bbre_primary))
            binding.tvTabSettings.setTextColor(getColor(R.color.bbre_primary))
            binding.tvTabSettings.setTypeface(null, android.graphics.Typeface.BOLD)
            
            // Reload user info into settings fields
            binding.etSettingsUsername.setText(currentUser)
            binding.etSettingsPassword.text?.clear()
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
        val clip = ClipData.newPlainText("BBre Key", text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        lastCopiedKey = text
        clipboardHandler.removeCallbacks(clearClipboardRunnable)
        clipboardHandler.postDelayed(clearClipboardRunnable, 45_000L)
        Toast.makeText(this, "Llave copiada al portapapeles (se limpiará en 45 s)", Toast.LENGTH_SHORT).show()
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
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Información de Llave")
            .setMessage(
                "• Creado por: ${if(key.createdBy.isEmpty()) currentUser else key.createdBy}\n" +
                "• Banco: ${key.type}\n" +
                "• Descripción: ${if(key.description.isEmpty()) "Sin descripción" else key.description}\n" +
                "• Valor de Llave: ${key.value}\n" +
                "• Fecha de Creación: ${if(key.creationDate.isEmpty()) "Desconocido" else key.creationDate}\n" +
                "• Fecha de Modificación: ${if(key.modificationDate.isEmpty()) "Sin modificaciones" else key.modificationDate}\n" +
                "• Versión más reciente: ${key.version}"
            )
            .setPositiveButton("Copiar Llave") { _, _ -> copyToClipboard(key.value) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun showKeyDialog(existingKey: UserKey?) {
        activeDialog?.dismiss()
        val dialogBinding = DialogKeyBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()
        activeDialog = dialog

        dialogBinding.tvDialogTitle.text = if (existingKey == null) "Agregar Nueva Llave" else "Modificar Llave"

        // Merge base banks with custom banks already saved by the user (sorted, no duplicates)
        val customBankOption = "+ Agregar nuevo banco..."
        val bankOptions = KeyUtils.buildBankOptions(currentUserKeys, customBankOption)
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, bankOptions)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerKeyType.adapter = spinnerAdapter

        // Clear validation errors automatically as the user types
        dialogBinding.etCustomBank.doAfterTextChanged { dialogBinding.tilCustomBank.error = null }
        dialogBinding.etDescription.doAfterTextChanged { dialogBinding.tilDescription.error = null }
        dialogBinding.etKeyValue.doAfterTextChanged { dialogBinding.tilKeyValue.error = null }

        // Control visibility of custom bank entry
        dialogBinding.spinnerKeyType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                dialogBinding.tilCustomBank.error = null
                if (bankOptions[position] == customBankOption) {
                    dialogBinding.tilCustomBank.visibility = View.VISIBLE
                } else {
                    dialogBinding.tilCustomBank.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Pre-populate if editing
        if (existingKey != null) {
            val bankIndex = bankOptions.indexOfFirst { it.equals(existingKey.type.trim(), ignoreCase = true) }
            if (bankIndex != -1) {
                dialogBinding.spinnerKeyType.setSelection(bankIndex)
            } else {
                dialogBinding.spinnerKeyType.setSelection(bankOptions.indexOf(customBankOption))
                dialogBinding.tilCustomBank.visibility = View.VISIBLE
                dialogBinding.etCustomBank.setText(existingKey.type)
            }
            dialogBinding.etDescription.setText(existingKey.description)
            dialogBinding.etKeyValue.setText(existingKey.value)
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            sessionManager.recordActivity()
            scheduleRemainingTimeout()

            // Clear previous errors before validating
            dialogBinding.tilCustomBank.error = null
            dialogBinding.tilDescription.error = null
            dialogBinding.tilKeyValue.error = null

            var hasError = false
            var type = dialogBinding.spinnerKeyType.selectedItem.toString()
            if (type == customBankOption) {
                val customBank = KeyUtils.normalizeField(dialogBinding.etCustomBank.text.toString())
                if (customBank.isEmpty()) {
                    dialogBinding.tilCustomBank.error = "Escribe el nombre del banco"
                    hasError = true
                } else {
                    type = customBank
                }
            }

            val description = KeyUtils.normalizeField(dialogBinding.etDescription.text.toString())
            val value = KeyUtils.normalizeKeyValue(dialogBinding.etKeyValue.text.toString())

            if (description.isEmpty()) {
                dialogBinding.tilDescription.error = "Ingresa una descripción"
                hasError = true
            }

            if (value.isEmpty()) {
                dialogBinding.tilKeyValue.error = "Ingresa un valor para la llave"
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
                            Toast.makeText(this@DashboardActivity, "Llave agregada exitosamente", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            loadKeysList()
                        }
                        is StorageOpResult.Duplicate -> {
                            dialogBinding.tilKeyValue.error = "Ya tienes esta llave registrada para este banco"
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
                            Toast.makeText(this@DashboardActivity, "Llave modificada exitosamente", Toast.LENGTH_SHORT).show()
                            dialog.dismiss()
                            loadKeysList()
                        }
                        is StorageOpResult.Duplicate -> {
                            dialogBinding.tilKeyValue.error = "Ya tienes esta llave registrada para este banco"
                        }
                        is StorageOpResult.Error -> {
                            Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                        is StorageOpResult.NotFound -> {
                            Toast.makeText(this@DashboardActivity, "No se encontró la llave a modificar", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showDeleteKeyConfirmation(userKey: UserKey) {
        activeDialog?.dismiss()
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Eliminar Llave")
            .setMessage("¿Estás seguro de que deseas eliminar esta llave? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                lifecycleScope.launch {
                    when (val result = storageManager.deleteKey(userKey.id)) {
                        is StorageOpResult.Success -> {
                            Toast.makeText(this@DashboardActivity, "Llave eliminada", Toast.LENGTH_SHORT).show()
                            loadKeysList()
                        }
                        is StorageOpResult.Error -> {
                            Toast.makeText(this@DashboardActivity, result.message, Toast.LENGTH_LONG).show()
                        }
                        is StorageOpResult.NotFound, is StorageOpResult.Duplicate -> Unit
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- Settings Tab Logic ---

    private fun setupSettingsTab() {
        binding.btnSaveProfile.setOnClickListener {
            val newUsername = binding.etSettingsUsername.text.toString().trim()
            val newPassword = binding.etSettingsPassword.text?.toString() ?: ""

            if (newUsername.isEmpty()) {
                binding.tilSettingsUsername.error = "El usuario no puede estar vacío"
                return@setOnClickListener
            }
            if (newUsername.any { it.isWhitespace() }) {
                binding.tilSettingsUsername.error = "El usuario no debe contener espacios"
                return@setOnClickListener
            }
            binding.tilSettingsUsername.error = null

            if (newPassword.isNotEmpty()) {
                if (newPassword.any { it.isWhitespace() }) {
                    binding.tilSettingsPassword.error = "La contraseña no debe contener espacios"
                    return@setOnClickListener
                }
                if (newPassword.length < 8) {
                    binding.tilSettingsPassword.error = "La nueva contraseña debe tener al menos 8 caracteres"
                    return@setOnClickListener
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
                        "No se encontró el usuario actual. Inicia sesión nuevamente.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }

                if (newUsername == foundUser.username && newPassword.isEmpty()) {
                    Toast.makeText(this@DashboardActivity, "No hay cambios para guardar", Toast.LENGTH_SHORT).show()
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
                                    "Perfil actualizado exitosamente",
                                    Toast.LENGTH_SHORT
                                ).show()
                                switchTab(true)
                            }
                            is StorageOpResult.Duplicate -> {
                                binding.tilSettingsUsername.error = "El nombre de usuario ya está en uso"
                            }
                            is StorageOpResult.NotFound -> {
                                Toast.makeText(
                                    this@DashboardActivity,
                                    "No se encontró el usuario para actualizar.",
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
                        title = "Confirmar identidad",
                        message = "Ingresa tu contraseña actual para cambiar tu contraseña.",
                        onAuthenticated = applyProfileUpdate
                    )
                } else {
                    applyProfileUpdate()
                }
            }
        }

        binding.btnLogout.setOnClickListener {
            performManualLogout()
        }

        binding.btnDeleteAccount.setOnClickListener {
            showDeleteAccountConfirmation()
        }

        // Add System / Key Info Dialog button in Configuración
        val btnSystemInfo = com.google.android.material.button.MaterialButton(this, null, com.google.android.material.R.attr.materialButtonStyle).apply {
            text = "Información de Llave y Sistema"
            id = View.generateViewId()
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
                bottomMargin = 16
            }
            setIconResource(android.R.drawable.ic_menu_info_details)
            setIconGravity(com.google.android.material.button.MaterialButton.ICON_GRAVITY_TEXT_START)
            // Style it to match primary/secondary button theme
            setBackgroundColor(getColor(R.color.bbre_primary))
            setTextColor(getColor(R.color.white))
            setOnClickListener {
                showSystemInfoDialog()
            }
        }

        // Add this button dynamically to the layout settings container
        val settingsLayoutContainer = binding.layoutSettings.getChildAt(0) as? android.widget.LinearLayout
        settingsLayoutContainer?.addView(btnSystemInfo, 3) // Add right above Logout button
    }

    private fun showSystemInfoDialog() {
        activeDialog?.dismiss()
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Información del Sistema BBre")
            .setMessage(
                "• Nombre de la App: BBre - NairuSoft\n" +
                "• Creado por: NairuSoft\n" +
                "• Propósito: Administración local de llaves y credenciales seguras\n" +
                "• Versión más reciente: 0.0.1"
            )
            .setPositiveButton("Aceptar", null)
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
            hint = "Contraseña actual"
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val etCurrentPassword = TextInputEditText(tilCurrentPassword.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        tilCurrentPassword.addView(etCurrentPassword)
        container.addView(tilCurrentPassword)

        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton("Confirmar", null)
            .setNegativeButton("Cancelar", null)
            .create()

        activeDialog = dialog
        dialog.setOnShowListener {
            val positiveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positiveBtn.setOnClickListener {
                val enteredPassword = etCurrentPassword.text?.toString() ?: ""
                if (enteredPassword.isEmpty()) {
                    tilCurrentPassword.error = "Ingresa tu contraseña actual"
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
                                tilCurrentPassword.error = "Contraseña actual incorrecta"
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
        activeDialog = AlertDialog.Builder(this)
            .setTitle("Eliminar Cuenta")
            .setMessage("¿Estás seguro de que deseas eliminar permanentemente tu cuenta '$currentUser'? Se eliminarán tu perfil y todas tus llaves registradas. Esta acción no se puede deshacer.")
            .setPositiveButton("Continuar") { _, _ ->
                requestReauthentication(
                    title = "Confirmar eliminación",
                    message = "Por seguridad, ingresa tu contraseña actual para eliminar permanentemente tu cuenta y tus llaves."
                ) {
                    lifecycleScope.launch {
                        when (val result = storageManager.deleteUser(currentUser)) {
                            is StorageOpResult.Success -> {
                                securityManager.clearBiometricEnrollment()
                                securityManager.removeSecureData(SessionManager.KEY_LAST_LOGGED_IN_USER)
                                Toast.makeText(
                                    this@DashboardActivity,
                                    "Cuenta eliminada exitosamente",
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
                                    "No se encontró la cuenta actual.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            is StorageOpResult.Duplicate -> Unit
                        }
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
