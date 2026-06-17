package com.nairusoft.bbre.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.nairusoft.bbre.R
import com.nairusoft.bbre.data.UserManager
import com.nairusoft.bbre.databinding.ActivityMyKeysBinding
import com.nairusoft.bbre.databinding.DialogCreateKeyBinding
import com.nairusoft.bbre.security.SecurityManager

/**
 * My Keys Activity - Display and manage user keys
 */
class MyKeysActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMyKeysBinding
    private lateinit var userManager: UserManager
    private lateinit var securityManager: SecurityManager
    private lateinit var keysAdapter: KeysAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMyKeysBinding.inflate(layoutInflater)
        setContentView(binding.root)

        userManager = UserManager.getInstance(this)
        securityManager = SecurityManager.getInstance(this)

        setupToolbar()
        setupRecyclerView()
        setupCreateButton()
        loadKeys()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            onBackPressed()
        }
    }

    private fun setupRecyclerView() {
        keysAdapter = KeysAdapter(
            onEditClick = { key -> showEditDialog(key) },
            onDeleteClick = { key -> confirmDeleteKey(key.id) }
        )
        binding.rvKeys.layoutManager = LinearLayoutManager(this)
        binding.rvKeys.adapter = keysAdapter
    }

    private fun setupCreateButton() {
        binding.btnCreateKey.setOnClickListener {
            showCreateKeyDialog()
        }
    }

    private fun loadKeys() {
        val currentUser = securityManager.getSecureData("current_user", "")
        if (currentUser.isEmpty()) {
            Toast.makeText(this, "Usuario no encontrado", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val keys = userManager.getUserKeys(currentUser)
        
        if (keys.isEmpty()) {
            binding.llEmptyState.visibility = View.VISIBLE
            binding.rvKeys.visibility = View.GONE
        } else {
            binding.llEmptyState.visibility = View.GONE
            binding.rvKeys.visibility = View.VISIBLE
            keysAdapter.submitList(keys)
        }
    }

    private fun showCreateKeyDialog() {
        val dialogBinding = DialogCreateKeyBinding.inflate(LayoutInflater.from(this))
        
        val keyTypes = arrayOf(
            getString(R.string.key_type_bank),
            getString(R.string.key_type_id),
            getString(R.string.key_type_phone),
            getString(R.string.key_type_email),
            getString(R.string.key_type_random)
        )
        
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, keyTypes)
        dialogBinding.actvKeyType.setAdapter(adapter)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .create()

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val selectedType = dialogBinding.actvKeyType.text.toString()
            val keyValue = dialogBinding.etKeyValue.text.toString().trim()

            if (selectedType.isEmpty()) {
                dialogBinding.tilKeyType.error = "Selecciona un tipo de llave"
                return@setOnClickListener
            }

            if (keyValue.isEmpty()) {
                dialogBinding.tilKeyValue.error = "Ingresa el valor de la llave"
                return@setOnClickListener
            }

            val currentUser = securityManager.getSecureData("current_user", "")
            val key = userManager.createKey(currentUser, selectedType, keyValue)

            if (key != null) {
                Toast.makeText(this, R.string.success_key_created, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadKeys()
            } else {
                Toast.makeText(this, R.string.error_key_exists, Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showEditDialog(key: UserManager.Key) {
        val dialogBinding = DialogCreateKeyBinding.inflate(LayoutInflater.from(this))
        
        dialogBinding.actvKeyType.setText(key.type, false)
        dialogBinding.etKeyValue.setText(key.value)

        val dialog = MaterialAlertDialogBuilder(this)
            .setView(dialogBinding.root)
            .setTitle(R.string.key_edit_title)
            .create()

        dialogBinding.btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        dialogBinding.btnSave.setOnClickListener {
            val newValue = dialogBinding.etKeyValue.text.toString().trim()

            if (newValue.isEmpty()) {
                dialogBinding.tilKeyValue.error = "Ingresa el valor de la llave"
                return@setOnClickListener
            }

            val success = userManager.updateKey(key.id, newValue)

            if (success) {
                Toast.makeText(this, R.string.success_key_updated, Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                loadKeys()
            } else {
                Toast.makeText(this, "Error al actualizar", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun confirmDeleteKey(keyId: String) {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.confirm_delete_key)
            .setPositiveButton(R.string.key_delete) { _, _ ->
                val success = userManager.deleteKey(keyId)
                if (success) {
                    Toast.makeText(this, R.string.success_key_deleted, Toast.LENGTH_SHORT).show()
                    loadKeys()
                } else {
                    Toast.makeText(this, "Error al eliminar", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.key_cancel, null)
            .show()
    }
}
