package com.asterisk.voip.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.asterisk.voip.R
import com.asterisk.voip.data.model.CallInfo
import com.asterisk.voip.data.model.PeerInfo
import com.asterisk.voip.databinding.ItemCallBinding
import com.asterisk.voip.databinding.ItemPeerBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class PeersAdapter : ListAdapter<PeerInfo, PeersAdapter.VH>(Diff) {
    private val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    inner class VH(val b: ItemPeerBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemPeerBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val p = getItem(position)
        val ctx = h.itemView.context
        h.b.tvPeerInitial.text = p.extension.takeLast(3)
        h.b.tvPeerExt.text = "Ext ${p.extension}"
        val ua = if (p.userAgent.isNotBlank()) " • ${p.userAgent.take(24)}" else ""
        h.b.tvPeerAddr.text = "${p.ip}:${p.port} • ${p.transport}$ua"
        val color = ctx.getColor(if (p.reachable) R.color.green else R.color.amber)
        h.b.tvPeerState.text = if (p.reachable) "Online" else "No reply ${time.format(Date(p.lastSeen))}"
        h.b.tvPeerState.setTextColor(color)
        h.b.tvPeerState.setCompoundDrawablesWithIntrinsicBounds(
            ctx.getDrawable(R.drawable.bg_dot)?.mutate()?.also { it.setTint(color); it.setBounds(0, 0, 24, 24) },
            null, null, null
        )
    }

    private object Diff : DiffUtil.ItemCallback<PeerInfo>() {
        override fun areItemsTheSame(a: PeerInfo, b: PeerInfo) = a.extension == b.extension
        override fun areContentsTheSame(a: PeerInfo, b: PeerInfo) = a == b
    }
}

class CallsAdapter : ListAdapter<CallInfo, CallsAdapter.VH>(Diff) {

    inner class VH(val b: ItemCallBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(h: VH, position: Int) {
        val c = getItem(position)
        h.b.ivCallKind.setImageResource(if (c.video) R.drawable.ic_video else R.drawable.ic_phone)
        h.b.tvCallParties.text = "${c.fromExt}  →  ${c.toExt}"
        val secs = ((System.currentTimeMillis() - c.startedAt) / 1000).coerceAtLeast(0)
        val dur = String.format(Locale.US, "%02d:%02d", secs / 60, secs % 60)
        val route = if (c.relayed) "Relay via server" else "Direct device ↔ device"
        h.b.tvCallMeta.text = "$route • ${if (c.video) "Video" else "Audio"} • $dur"
        h.b.tvCallState.text = c.state
    }

    private object Diff : DiffUtil.ItemCallback<CallInfo>() {
        override fun areItemsTheSame(a: CallInfo, b: CallInfo) = a.id == b.id
        override fun areContentsTheSame(a: CallInfo, b: CallInfo) = a == b
    }
}
