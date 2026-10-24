// FILE: ./app/src/main/java/com/asterisk/voip/ui/dialplan/VoicePromptsAdapter.kt
package com.asterisk.voip.ui.dialplan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asterisk.voip.data.model.VoicePrompt
import com.asterisk.voip.data.model.VoicePromptType
import com.asterisk.voip.databinding.ItemVoicePromptBinding

class VoicePromptsAdapter(
    private val onPlayClick: (VoicePrompt) -> Unit,
    private val onEditClick: (VoicePrompt) -> Unit,
    private val onDeleteClick: (VoicePrompt) -> Unit,
    private val onToggleClick: (VoicePrompt, Boolean) -> Unit
) : ListAdapter<VoicePrompt, VoicePromptsAdapter.PromptViewHolder>(PromptDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PromptViewHolder {
        val binding = ItemVoicePromptBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return PromptViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PromptViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class PromptViewHolder(
        private val binding: ItemVoicePromptBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(prompt: VoicePrompt) {
            binding.apply {
                tvPromptName.text = prompt.name
                tvPromptDescription.text = prompt.description.ifEmpty { "No description" }
                tvPromptType.text = formatType(prompt.fileType)
                tvPromptDuration.text = formatDuration(prompt.duration)
                tvPromptLanguage.text = prompt.language

                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = prompt.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggleClick(prompt, isChecked)
                }

                btnPlay.setOnClickListener { onPlayClick(prompt) }
                btnEdit.setOnClickListener { onEditClick(prompt) }
                btnDelete.setOnClickListener { onDeleteClick(prompt) }
            }
        }

        private fun formatType(type: VoicePromptType): String {
            return when (type) {
                VoicePromptType.AUDIO_FILE -> "🎵 Audio File"
                VoicePromptType.TTS -> "🗣️ Text-to-Speech"
                VoicePromptType.SYSTEM_TONE -> "🔔 System Tone"
            }
        }

        private fun formatDuration(millis: Long): String {
            if (millis == 0L) return "Unknown"
            val seconds = millis / 1000
            return if (seconds < 60) {
                "${seconds}s"
            } else {
                val minutes = seconds / 60
                val remainingSeconds = seconds % 60
                "${minutes}m ${remainingSeconds}s"
            }
        }
    }

    class PromptDiffCallback : DiffUtil.ItemCallback<VoicePrompt>() {
        override fun areItemsTheSame(oldItem: VoicePrompt, newItem: VoicePrompt) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: VoicePrompt, newItem: VoicePrompt) =
            oldItem == newItem
    }
}