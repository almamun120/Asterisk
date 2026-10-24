// FILE: ./app/src/main/java/com/asterisk/voip/ui/dialplan/DialPlanRulesAdapter.kt
package com.asterisk.voip.ui.dialplan

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asterisk.voip.data.model.DialPlanRule
import com.asterisk.voip.databinding.ItemDialplanRuleBinding

class DialPlanRulesAdapter(
    private val onEditClick: (DialPlanRule) -> Unit,
    private val onDeleteClick: (DialPlanRule) -> Unit,
    private val onToggleClick: (DialPlanRule, Boolean) -> Unit
) : ListAdapter<DialPlanRule, DialPlanRulesAdapter.RuleViewHolder>(RuleDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RuleViewHolder {
        val binding = ItemDialplanRuleBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return RuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: RuleViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class RuleViewHolder(
        private val binding: ItemDialplanRuleBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: DialPlanRule) {
            binding.apply {
                tvRuleName.text = rule.name
                tvRuleDescription.text = rule.description.ifEmpty { "No description" }
                tvPattern.text = "Pattern: ${rule.pattern}"
                tvCondition.text = "When: ${formatCondition(rule.condition)}"
                tvAction.text = "Then: ${formatAction(rule.action)}"
                tvPriority.text = "Priority: ${rule.priority}"

                switchEnabled.setOnCheckedChangeListener(null)
                switchEnabled.isChecked = rule.enabled
                switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onToggleClick(rule, isChecked)
                }

                btnEdit.setOnClickListener { onEditClick(rule) }
                btnDelete.setOnClickListener { onDeleteClick(rule) }
            }
        }

        private fun formatCondition(condition: com.asterisk.voip.data.model.DialPlanCondition): String {
            return when (condition) {
                com.asterisk.voip.data.model.DialPlanCondition.OFFLINE -> "Extension Offline"
                com.asterisk.voip.data.model.DialPlanCondition.BUSY -> "Extension Busy"
                com.asterisk.voip.data.model.DialPlanCondition.NO_ANSWER -> "No Answer"
                com.asterisk.voip.data.model.DialPlanCondition.REJECTED -> "Call Rejected"
                com.asterisk.voip.data.model.DialPlanCondition.NETWORK_ERROR -> "Network Error"
                com.asterisk.voip.data.model.DialPlanCondition.INVALID_NUMBER -> "Invalid Number"
                com.asterisk.voip.data.model.DialPlanCondition.FORBIDDEN -> "Forbidden"
                com.asterisk.voip.data.model.DialPlanCondition.TIMEOUT -> "Timeout"
                com.asterisk.voip.data.model.DialPlanCondition.UNAVAILABLE -> "Unavailable"
                com.asterisk.voip.data.model.DialPlanCondition.DO_NOT_DISTURB -> "Do Not Disturb"
                com.asterisk.voip.data.model.DialPlanCondition.CALL_FORWARDING -> "Call Forwarding"
                com.asterisk.voip.data.model.DialPlanCondition.AFTER_HOURS -> "After Hours"
                com.asterisk.voip.data.model.DialPlanCondition.ALWAYS -> "Always"
            }
        }

        private fun formatAction(action: com.asterisk.voip.data.model.DialPlanAction): String {
            return when (action) {
                com.asterisk.voip.data.model.DialPlanAction.PLAY_ANNOUNCEMENT -> "Play Announcement"
                com.asterisk.voip.data.model.DialPlanAction.VOICEMAIL -> "Send to Voicemail"
                com.asterisk.voip.data.model.DialPlanAction.FORWARD_TO_EXTENSION -> "Forward to Extension"
                com.asterisk.voip.data.model.DialPlanAction.FORWARD_TO_QUEUE -> "Forward to Queue"
                com.asterisk.voip.data.model.DialPlanAction.FORWARD_TO_IVR -> "Forward to IVR"
                com.asterisk.voip.data.model.DialPlanAction.HANGUP -> "Hang Up"
                com.asterisk.voip.data.model.DialPlanAction.CALLBACK -> "Schedule Callback"
                com.asterisk.voip.data.model.DialPlanAction.RETRY -> "Retry"
                com.asterisk.voip.data.model.DialPlanAction.CUSTOM -> "Custom Action"
            }
        }
    }

    class RuleDiffCallback : DiffUtil.ItemCallback<DialPlanRule>() {
        override fun areItemsTheSame(oldItem: DialPlanRule, newItem: DialPlanRule) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: DialPlanRule, newItem: DialPlanRule) =
            oldItem == newItem
    }
}