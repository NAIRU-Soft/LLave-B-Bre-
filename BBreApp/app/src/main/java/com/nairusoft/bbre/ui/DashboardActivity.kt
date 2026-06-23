package com.nairusoft.bbre.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding
import com.nairusoft.bbre.databinding.DialogKeyBinding
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.StorageManager
import com.nairusoft.bbre.security.User
import com.nairusoft.bbre.security.UserKey
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var securityManager: SecurityManager
    private lateinit var storageManager: StorageManager
    private lateinit var keysAdapter: KeysAdapter
    private var currentUser: String = ""

    private val logoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logoutRunnable = Runnable {
        autoLogout()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetInactivityTimer()
    }

    override fun onResume() {
        super.onResume()
        resetInactivityTimer()
    }

    override fun onPause() {
        super.onPause()
        logoutHandler.removeCallbacks(logoutRunnable)
    }

    private fun resetInactivityTimer() {
        logoutHandler.removeCallbacks(logoutRunnable)
        logoutHandler.postDelayed(logoutRunnable, 5 * 60 * 1000) // 5 minutes
    }

    private fun autoLogout() {
        Toast.makeText(this, "Sesión cerrada por inactividad", Toast.LENGTH_LONG).show()
        performManualLogout()
    }

    private fun performManualLogout() {
        securityManager.storeSecureData("session_username", "")
        navigateToLogin()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        securityManager = SecurityManager.getInstance(this)
        storageManager = StorageManager.getInstance(this)

        currentUser = securityManager.getSecureData("session_username")
        if (currentUser.isEmpty()) {
            navigateToLogin()
            return
        }

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
        startActivity(Intent(this, LoginActivity::class.java))
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
        val keys = storageManager.getKeysForUser(currentUser)
        if (keys.isEmpty()) {
            binding.llEmptyState.visibility = View.VISIBLE
            binding.rvKeys.visibility = View.GONE
        } else {
            binding.llEmptyState.visibility = View.GONE
            binding.rvKeys.visibility = View.VISIBLE
            keysAdapter.updateData(keys)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("BBre Key", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Llave copiada: $text", Toast.LENGTH_SHORT).show()
    }

    private fun showKeyInfoDialog(key: UserKey) {
        AlertDialog.Builder(this)
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
        val dialogBinding = DialogKeyBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvDialogTitle.text = if (existingKey == null) "Agregar Nueva Llave" else "Modificar Llave"

        // Setup Spinners/Adapters for banks
        val defaultBanks = mutableListOf("Banco de la República", "Bancolombia", "Davivienda", "Nequi", "Daviplata", "+ Agregar nuevo banco...")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, defaultBanks)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerKeyType.adapter = spinnerAdapter

        // Control visibility of custom bank entry
        dialogBinding.spinnerKeyType.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (defaultBanks[position] == "+ Agregar nuevo banco...") {
                    dialogBinding.tilCustomBank.visibility = View.VISIBLE
                } else {
                    dialogBinding.tilCustomBank.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // Pre-populate if editing
        if (existingKey != null) {
            val bankIndex = defaultBanks.indexOf(existingKey.type)
            if (bankIndex != -1) {
                dialogBinding.spinnerKeyType.setSelection(bankIndex)
            } else {
                // If it's a custom bank, set spinner to custom and fill custom text
                dialogBinding.spinnerKeyType.setSelection(defaultBanks.indexOf("+ Agregar nuevo banco..."))
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
            var type = dialogBinding.spinnerKeyType.selectedItem.toString()
            if (type == "+ Agregar nuevo banco...") {
                val customBank = dialogBinding.etCustomBank.text.toString().trim()
                if (customBank.isEmpty()) {
                    dialogBinding.tilCustomBank.error = "Escribe el nombre del banco"
                    return@setOnClickListener
                }
                type = customBank
            }
            
            val description = dialogBinding.etDescription.text.toString().trim()
            val value = dialogBinding.etKeyValue.text.toString().trim()

            if (description.isEmpty()) {
                dialogBinding.tilDescription.error = "Ingresa una descripción"
                return@setOnClickListener
            }

            if (value.isEmpty()) {
                dialogBinding.tilKeyValue.error = "Ingresa un valor para la llave"
                return@setOnClickListener
            }

            val currentTime = getCurrentFormattedDate()

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
                if (storageManager.addKey(newKey)) {
                    Toast.makeText(this, "Llave agregada exitosamente", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadKeysList()
                } else {
                    dialogBinding.tilKeyValue.error = "Esta llave ya existe en el sistema"
                }
            } else {
                // Update key
                val updatedKey = existingKey.copy(
                    type = type, 
                    value = value,
                    description = description,
                    modificationDate = currentTime
                )
                if (storageManager.updateKey(updatedKey)) {
                    Toast.makeText(this, "Llave modificada exitosamente", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadKeysList()
                } else {
                    dialogBinding.tilKeyValue.error = "Esta llave ya existe en el sistema"
                }
            }
        }

        dialog.show()
    }

    private fun showDeleteKeyConfirmation(userKey: UserKey) {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Llave")
            .setMessage("¿Estás seguro de que deseas eliminar esta llave? Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar") { _, _ ->
                if (storageManager.deleteKey(userKey.id)) {
                    Toast.makeText(this, "Llave eliminada", Toast.LENGTH_SHORT).show()
                    loadKeysList()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // --- Settings Tab Logic ---

    private fun setupSettingsTab() {
        binding.btnSaveProfile.setOnClickListener {
            val newUsername = binding.etSettingsUsername.text.toString().trim()
            val newPassword = binding.etSettingsPassword.text.toString().trim()

            if (newUsername.isEmpty()) {
                binding.tilSettingsUsername.error = "El usuario no puede estar vacío"
                return@setOnClickListener
            }
            binding.tilSettingsUsername.error = null

            val users = storageManager.getUsers()
            val foundUser = users.find { it.username.equals(currentUser, ignoreCase = true) }
            
            if (foundUser != null) {
                val newHash = if (newPassword.isNotEmpty()) {
                    if (newPassword.length < 8) {
                        binding.tilSettingsPassword.error = "La nueva contraseña debe tener al menos 8 caracteres"
                        return@setOnClickListener
                    }
                    securityManager.hashData(newPassword)
                } else {
                    foundUser.passwordHash
                }
                binding.tilSettingsPassword.error = null

                val updatedUser = User(newUsername, newHash)
                if (storageManager.updateUser(currentUser, updatedUser)) {
                    currentUser = newUsername
                    securityManager.storeSecureData("session_username", newUsername)
                    securityManager.storeSecureData("last_logged_in_user", newUsername)
                    Toast.makeText(this, "Perfil actualizado exitosamente", Toast.LENGTH_SHORT).show()
                    switchTab(true)
                } else {
                    binding.tilSettingsUsername.error = "El nombre de usuario ya está en uso"
                }
            }
        }

        binding.btnLogout.setOnClickListener {
            securityManager.storeSecureData("session_username", "")
            navigateToLogin()
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
        AlertDialog.Builder(this)
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

    private fun showDeleteAccountConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Cuenta")
            .setMessage("¿Estás seguro de que deseas eliminar permanentemente tu cuenta? Se eliminarán todas tus llaves y tu perfil. Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar permanentemente") { _, _ ->
                if (storageManager.deleteUser(currentUser)) {
                    securityManager.storeSecureData("session_username", "")
                    securityManager.storeSecureData("last_logged_in_user", "")
                    Toast.makeText(this, "Cuenta eliminada exitosamente", Toast.LENGTH_LONG).show()
                    navigateToLogin()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
