package com.asterisk.voip.ui.console

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asterisk.voip.R
import com.asterisk.voip.data.model.ConsoleLog
import com.asterisk.voip.data.model.LogLevel
import com.asterisk.voip.databinding.ItemConsoleLogBinding
import java.text.SimpleDateFormat
import java.util.*

class ConsoleAdapter : ListAdapter<ConsoleLog, ConsoleAdapter.LogViewHolder>(LogDiffCallback()) {

    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemConsoleLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(getItem(position))

    inner class LogViewHolder(private val binding: ItemConsoleLogBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(log: ConsoleLog) {
            binding.apply {
                tvTimestamp.text = dateFormat.format(Date(log.timestamp))
                tvLevel.text = log.level.name[0].toString()
                tvTag.text = log.tag
                tvMessage.text = log.message
                val color = when (log.level) {
                    LogLevel.ERROR -> R.color.log_error
                    LogLevel.WARNING -> R.color.log_warning
                    LogLevel.INFO -> R.color.log_info
                    LogLevel.DEBUG -> R.color.log_debug
                    LogLevel.VERBOSE -> R.color.log_verbose
                }
                tvLevel.setTextColor(ContextCompat.getColor(itemView.context, color))
                tvMessage.setTextColor(ContextCompat.getColor(itemView.context, color))
            }
        }
    }

    class LogDiffCallback : DiffUtil.ItemCallback<ConsoleLog>() {
        override fun areItemsTheSame(oldItem: ConsoleLog, newItem: ConsoleLog) = oldItem.timestamp == newItem.timestamp
        override fun areContentsTheSame(oldItem: ConsoleLog, newItem: ConsoleLog) = oldItem == newItem
    }
}