package com.asterisk.voip.ui.gateways

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asterisk.voip.R
import com.asterisk.voip.data.model.Trunk
import com.asterisk.voip.data.model.TrunkInfo
import com.asterisk.voip.databinding.ItemGatewayBinding

class GatewaysAdapter(
    private val onEdit: (Trunk) -> Unit,
    private val onDelete: (Trunk) -> Unit,
    private val onToggle: (Trunk, Boolean) -> Unit,
    private val onRetry: (Trunk) -> Unit
) : ListAdapter<Trunk, GatewaysAdapter.VH>(Diff) {

    private var status: Map<Long, TrunkInfo> = emptyMap()
    private var running = false

    fun setStatus(s: Map<Long, TrunkInfo>, serverRunning: Boolean) {
        if (s != status || serverRunning != running) {
            status = s; running = serverRunning
            notifyItemRangeChanged(0, itemCount)
        }
    }

    inner class VH(val b: ItemGatewayBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemGatewayBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val t = getItem(position)
        val ctx = h.itemView.context
        val info = status[t.id]
        val (label, colorRes) = when {
            !t.enabled -> "Disabled" to R.color.text_secondary
            !running -> "Server stopped" to R.color.text_secondary
            info == null -> "Starting…" to R.color.amber
            info.state == "REGISTERED" -> "Registered" to R.color.green
            info.state == "REACHABLE" -> "Reachable" to R.color.green
            info.state == "NO-REG" -> "No registration" to R.color.amber
            info.state == "REGISTERING" || info.state == "RESOLVING" || info.state == "STARTING" -> "Connecting…" to R.color.amber
            else -> info.state.lowercase().replaceFirstChar { it.uppercase() } to R.color.red
        }
        val color = ctx.getColor(colorRes)
        h.b.tvGwName.text = t.name
        h.b.tvGwState.text = if (info != null && running && t.enabled && info.detail.isNotBlank()) "$label · ${info.detail}" else label
        h.b.tvGwState.setTextColor(color)
        h.b.tvGwState.setCompoundDrawablesWithIntrinsicBounds(
            ctx.getDrawable(R.drawable.bg_dot)?.mutate()?.also { it.setTint(color); it.setBounds(0, 0, 24, 24) },
            null, null, null
        )
        h.b.tvGwInfo.text = "${t.username}@${t.domain.ifBlank { t.host }}:${t.port}" +
            (info?.let { if (it.publicAddr.isNotEmpty()) " • public ${it.publicAddr}" else "" } ?: "") +
            (info?.let { if (it.activeCalls > 0) " • ${it.activeCalls} call(s)" else "" } ?: "")
        h.b.tvGwRoutes.text = "Out: ${t.outPrefixes.ifBlank { "—" }}" +
            (if (t.stripDigits > 0) " (strip ${t.stripDigits})" else "") +
            (if (t.addPrefix.isNotBlank()) " (+${t.addPrefix})" else "") +
            "  •  In: ${t.inboundTarget.ifBlank { if (t.inboundMap.isNotBlank()) "per number" else "—" }}"
        h.b.switchGwEnabled.setOnCheckedChangeListener(null)
        h.b.switchGwEnabled.isChecked = t.enabled
        h.b.switchGwEnabled.setOnCheckedChangeListener { _, on -> onToggle(t, on) }
        h.b.btnGwEdit.setOnClickListener { onEdit(t) }
        h.b.btnGwDelete.setOnClickListener { onDelete(t) }
        h.b.btnGwRetry.setOnClickListener { onRetry(t) }
        h.b.btnGwRetry.isEnabled = t.enabled && running && t.register
    }

    private object Diff : DiffUtil.ItemCallback<Trunk>() {
        override fun areItemsTheSame(a: Trunk, b: Trunk) = a.id == b.id
        override fun areContentsTheSame(a: Trunk, b: Trunk) = a == b
    }
}
