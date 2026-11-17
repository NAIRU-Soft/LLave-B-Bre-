package com.nairusoft.bbre.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nairusoft.bbre.R
import com.nairusoft.bbre.databinding.ItemKeyBinding
import com.nairusoft.bbre.security.UserKey

/**
 * RecyclerView adapter backed by [ListAdapter] and [DiffUtil] for rendering [UserKey] rows.
 */
class KeysAdapter(
    private val onRowClick: (UserKey) -> Unit,
    private val onInfoClick: (UserKey) -> Unit,
    private val onEditClick: (UserKey) -> Unit,
    private val onDeleteClick: (UserKey) -> Unit
) : ListAdapter<UserKey, KeysAdapter.KeyViewHolder>(KeyDiffCallback) {

    inner class KeyViewHolder(val binding: ItemKeyBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): KeyViewHolder {
        val binding = ItemKeyBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return KeyViewHolder(binding)
    }

    override fun onBindViewHolder(holder: KeyViewHolder, position: Int) {
        val key = getItem(position)
        val context = holder.itemView.context
        holder.binding.tvKeyType.text = key.type
        holder.binding.tvKeyDescription.text = context.getString(R.string.item_description_format, key.description)
        holder.binding.tvKeyValue.text = context.getString(R.string.item_key_format, key.value)

        // Make the whole row click trigger row click action (which copies the key)
        holder.itemView.setOnClickListener { onRowClick(key) }

        holder.binding.btnInfo.setOnClickListener { onInfoClick(key) }
        holder.binding.btnEdit.setOnClickListener { onEditClick(key) }
        holder.binding.btnDelete.setOnClickListener { onDeleteClick(key) }
    }

    /**
     * Submits a defensive copy of [newKeys] so [DiffUtil] diffs incremental changes accurately.
     */
    fun updateData(newKeys: List<UserKey>) {
        submitList(newKeys.toList())
    }

    private object KeyDiffCallback : DiffUtil.ItemCallback<UserKey>() {
        override fun areItemsTheSame(oldItem: UserKey, newItem: UserKey): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UserKey, newItem: UserKey): Boolean =
            oldItem == newItem
    }
}
