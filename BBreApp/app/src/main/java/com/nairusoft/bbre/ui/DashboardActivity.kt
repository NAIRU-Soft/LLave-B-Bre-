package com.nairusoft.bbre.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.RadioButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ActivityDashboardBinding
import com.nairusoft.bbre.databinding.DialogKeyBinding
import com.nairusoft.bbre.security.SecurityManager
import com.nairusoft.bbre.security.StorageManager
import com.nairusoft.bbre.security.User
import com.nairusoft.bbre.security.UserKey
import java.util.UUID

class DashboardActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDashboardBinding
    private lateinit var securityManager: SecurityManager
    private lateinit var storageManager: StorageManager
    private lateinit var keysAdapter: KeysAdapter
    private var currentUser: String = ""

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

    // --- Keys Tab Logic ---

    private fun setupKeysTab() {
        keysAdapter = KeysAdapter(
            emptyList(),
            onCopyClick = { userKey -> copyToClipboard(userKey.value) },
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
        Toast.makeText(this, "Llave copiada al portapapeles", Toast.LENGTH_SHORT).show()
    }

    private fun showKeyDialog(existingKey: UserKey?) {
        val dialogBinding = DialogKeyBinding.inflate(LayoutInflater.from(this))
        val dialog = AlertDialog.Builder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.tvDialogTitle.text = if (existingKey == null) "Agregar Nueva Llave" else "Modificar Llave"

        // Setup Spinners/Adapters for banks
        val banks = arrayOf("Banco de la República", "Bancolombia", "Davivienda", "Nequi", "Daviplata")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, banks)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        dialogBinding.spinnerKeyType.adapter = spinnerAdapter

        // Pre-populate if editing
        if (existingKey != null) {
            val bankIndex = banks.indexOf(existingKey.type)
            if (bankIndex != -1) {
                dialogBinding.spinnerKeyType.setSelection(bankIndex)
            }
            dialogBinding.etKeyValue.setText(existingKey.value)
            
            // Lock category changing during edits for consistency
            dialogBinding.rgCategory.visibility = View.GONE
            dialogBinding.tilKeyValue.visibility = View.VISIBLE
        } else {
            // Logic for Category changes to show/hide value or generate random code
            dialogBinding.rgCategory.setOnCheckedChangeListener { _, checkedId ->
                if (checkedId == R.id.rbRandom) {
                    val randomCode = UUID.randomUUID().toString().substring(0, 12).uppercase()
                    dialogBinding.etKeyValue.setText(randomCode)
                    dialogBinding.tilKeyValue.isEnabled = false
                } else {
                    dialogBinding.etKeyValue.text?.clear()
                    dialogBinding.tilKeyValue.isEnabled = true
                }
            }
        }

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val type = dialogBinding.spinnerKeyType.selectedItem.toString()
            val value = dialogBinding.etKeyValue.text.toString().trim()

            if (value.isEmpty()) {
                dialogBinding.tilKeyValue.error = "Ingresa un valor para la llave"
                return@setOnClickListener
            }

            if (existingKey == null) {
                // Add key
                val newKey = UserKey(
                    id = UUID.randomUUID().toString(),
                    type = type,
                    value = value,
                    username = currentUser
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
                val updatedKey = existingKey.copy(type = type, value = value)
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
    }

    private fun showDeleteAccountConfirmation() {
        AlertDialog.Builder(this)
            .setTitle("Eliminar Cuenta")
            .setMessage("¿Estás seguro de que deseas eliminar permanentemente tu cuenta? Se eliminarán todas tus llaves y tu perfil. Esta acción no se puede deshacer.")
            .setPositiveButton("Eliminar permanentemente") { _, _ ->
                if (storageManager.deleteUser(currentUser)) {
                    securityManager.storeSecureData("session_username", "")
                    Toast.makeText(this, "Cuenta eliminada exitosamente", Toast.LENGTH_LONG).show()
                    navigateToLogin()
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
}
