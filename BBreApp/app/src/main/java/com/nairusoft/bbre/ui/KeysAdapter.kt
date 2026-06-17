package com.nairusoft.bbre.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nairusoft.bbre.data.UserManager
import com.nairusoft.bbre.databinding.ItemKeyBinding

/**
 * KeysAdapter - RecyclerView adapter for displaying user keys
 */
class KeysAdapter(
    private val onEditClick: (UserManager.Key) -> Unit,
    private val onDeleteClick: (UserManager.Key) -> Unit
) : ListAdapter<UserManager.Key, KeysAdapter.KeyViewHolder>(KeyDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
        val binding = ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return KeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class KeyViewHolder(private val binding: ItemKeyBinding) : RecyclerView.ViewHolder(binding.root) {
        
        fun bind(key: UserManager.Key) {
            binding.tvKeyType.text = "${binding.root.context.getString(R.string.my_keys_type)} ${key.type}"
            binding.tvKeyValue.text = "${binding.root.context.getString(R.string.my_keys_value)} ${key.value}"
            
            binding.btnEdit.setOnClickListener {
                onEditClick(key)
            }
            
            binding.btnDelete.setOnClickListener {
                onDeleteClick(key)
            }
        }
    }

    class KeyDiffCallback : DiffUtil.ItemCallback<UserManager.Key>() {
        override fun areItemsTheSame(oldItem: UserManager.Key, newItem: UserManager.Key): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: UserManager.Key, newItem: UserManager.Key): Boolean {
            return oldItem == newItem
        }
    }
}
