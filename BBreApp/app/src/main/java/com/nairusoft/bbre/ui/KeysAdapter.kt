package com.nairusoft.bbre.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nairusoft.bbre.databinding.ItemKeyBinding
import com.nairusoft.bbre.security.UserKey

class KeysAdapter(
    private var keysList: List<UserKey>,
    private val onRowClick: (UserKey) -> Unit,
    private val onInfoClick: (UserKey) -> Unit,
    private val onEditClick: (UserKey) -> Unit,
    private val onDeleteClick: (UserKey) -> Unit
) : RecyclerView.Adapter<KeysAdapter.KeyViewHolder>() {

    inner class KeyViewHolder(val binding: ItemKeyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
        val binding = ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return KeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
        val key = keysList[position]
        holder.binding.tvKeyType.text = key.type
        holder.binding.tvKeyDescription.text = "Descripción: ${key.description}"
        holder.binding.tvKeyValue.text = "Llave: ${key.value}"

        // Make the whole row click trigger row click action (which copies the key)
        holder.itemView.setOnClickListener { onRowClick(key) }

        holder.binding.btnInfo.setOnClickListener { onInfoClick(key) }
        holder.binding.btnEdit.setOnClickListener { onEditClick(key) }
        holder.binding.btnDelete.setOnClickListener { onDeleteClick(key) }
    }

    override fun getItemCount(): Int = keysList.size

    fun updateData(newKeys: List<UserKey>) {
        keysList = newKeys
        notifyDataSetChanged()
    }
}
