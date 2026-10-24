package com.asterisk.voip.ui.extensions

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asterisk.voip.R
import com.asterisk.voip.data.model.Extension
import com.asterisk.voip.databinding.ItemExtensionBinding

class ExtensionsAdapter(
    private val onEditClick: (Extension) -> Unit,
    private val onDeleteClick: (Extension) -> Unit,
    private val onToggleClick: (Extension, Boolean) -> Unit
) : ListAdapter<Extension, ExtensionsAdapter.ExtensionViewHolder>(ExtensionDiffCallback()) {

    private var online: Set<String> = emptySet()

    fun setOnline(set: Set<String>) {
        if (set != online) { online = set; notifyItemRangeChanged(0, itemCount) }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExtensionViewHolder {
        val binding = ItemExtensionBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ExtensionViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ExtensionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ExtensionViewHolder(private val binding: ItemExtensionBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(extension: Extension) {
            binding.apply {
                tvExtensionNumber.text = extension.extensionNumber.takeLast(4)
                val ctx = root.context
                val isOn = extension.enabled && online.contains(extension.extensionNumber)
                val color = ctx.getColor(when { !extension.enabled -> R.color.text_secondary; isOn -> R.color.green; else -> R.color.amber })
                tvOnline.text = when { !extension.enabled -> "Disabled"; isOn -> "Online"; else -> "Offline" }
                tvOnline.setTextColor(color)
                tvOnline.setCompoundDrawablesWithIntrinsicBounds(
                    ctx.getDrawable(R.drawable.bg_dot)?.mutate()?.also { it.setTint(color); it.setBounds(0, 0, 24, 24) },
                    null, null, null
                )
                tvExtensionName.text = "${extension.extensionNumber} · ${extension.name}"
                tvCallerId.text = extension.callerID
                tvContext.text = extension.context
                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = extension.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked -> onToggleClick(extension, isChecked) }
                btnEdit.setOnClickListener { onEditClick(extension) }
                btnDelete.setOnClickListener { onDeleteClick(extension) }
            }
        }
    }

    class ExtensionDiffCallback : DiffUtil.ItemCallback<Extension>() {
        override fun areItemsTheSame(oldItem: Extension, newItem: Extension) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Extension, newItem: Extension) = oldItem == newItem
    }
}