package com.nairusoft.bbre.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.nairusoft.bbre.databinding.ItemKeyBinding
import com.nairusoft.bbre.security.UserKey

class KeysAdapter(
    private var keysList: List<UserKey>,
    private val onCopyClick: (UserKey) -> Unit,
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
        holder.binding.tvKeyValue.text = key.value

        holder.binding.btnCopy.setOnClickListener { onCopyClick(key) }
        holder.binding.btnEdit.setOnClickListener { onEditClick(key) }
        holder.binding.btnDelete.setOnClickListener { onDeleteClick(key) }
    }

    override fun getItemCount(): Int = keysList.size

    fun updateData(newKeys: List<UserKey>) {
        keysList = newKeys
        notifyDataSetChanged()
    }
}
