package com.asterisk.voip.ui.dashboard

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.asterisk.voip.R
import com.asterisk.voip.databinding.FragmentDashboardBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DashboardFragment : Fragment() {

    private var _binding: FragmentDashboardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DashboardViewModel by viewModels()

    private val peersAdapter = PeersAdapter()
    private val callsAdapter = CallsAdapter()
    private var updatingToggle = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDashboardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        updateBatteryCard()
    }

    private fun setupUI() {
        binding.recyclerPeers.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerPeers.adapter = peersAdapter
        binding.recyclerCalls.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerCalls.adapter = callsAdapter

        binding.btnPower.setOnClickListener { viewModel.toggleServer(!viewModel.isRunning.value) }
        binding.btnReload.setOnClickListener { viewModel.reloadConfiguration() }

        binding.toggleMediaMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || updatingToggle) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btn_mode_relay -> "relay"
                R.id.btn_mode_direct -> "direct"
                else -> "auto"
            }
            viewModel.setMediaMode(mode)
            binding.tvMediaModeHint.text = hintFor(mode)
        }

        binding.btnBattery.setOnClickListener { requestBatteryExemption() }
    }

    private fun hintFor(mode: String) = when (mode) {
        "relay" -> "Relay: every call's audio/video passes through this server. Works across any network and NAT."
        "direct" -> "Direct: the server only connects the call; devices send audio/video straight to each other. Needs both devices on the same network."
        else -> "Auto: direct when both devices are on the same network, relay otherwise."
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.serverStatus.collectLatest { status ->
                binding.apply {
                    val ctx = requireContext()
                    val color = ctx.getColor(if (status.isRunning) R.color.green else R.color.red)
                    tvStatus.text = if (status.isRunning) "Running" else "Stopped"
                    tvStatus.setTextColor(color)
                    viewStatusDot.backgroundTintList = android.content.res.ColorStateList.valueOf(color)
                    tvStatusSub.text = if (status.isRunning)
                        "${status.registeredPeers} online • ${status.activeCalls} call(s)" else "Tap Start to accept calls"
                    btnPower.text = if (status.isRunning) "Stop" else "Start"

                    tvUptime.text = formatUptime(status.uptime)
                    tvActiveCalls.text = status.activeCalls.toString()
                    tvRegisteredPeers.text = status.registeredPeers.toString()
                    tvSipPort.text = status.sipPort.toString()
                    tvBindAddress.text = status.bindAddress.ifEmpty { "—" }
                    tvRtpRange.text = status.rtpRange.ifEmpty { "—" }
                    progressCpu.progress = status.cpuUsage.toInt()
                    tvCpuUsage.text = "${status.cpuUsage.toInt()}%"
                    progressMemory.progress = status.memoryUsage.toInt()
                    tvMemoryUsage.text = "${status.memoryUsage.toInt()}%"
                    tvRelayRate.text = "${status.relayKbps} kbps"

                    peersAdapter.submitList(status.peers)
                    tvNoPeers.visibility = if (status.peers.isEmpty()) View.VISIBLE else View.GONE
                    callsAdapter.submitList(status.calls.toList())
                    tvNoCalls.visibility = if (status.calls.isEmpty()) View.VISIBLE else View.GONE

                    val id = when (status.mediaMode) {
                        "relay" -> R.id.btn_mode_relay
                        "direct" -> R.id.btn_mode_direct
                        else -> R.id.btn_mode_auto
                    }
                    if (toggleMediaMode.checkedButtonId != id) {
                        updatingToggle = true
                        toggleMediaMode.check(id)
                        updatingToggle = false
                    }
                    tvMediaModeHint.text = hintFor(status.mediaMode)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.extensionCount.collectLatest { count ->
                binding.tvExtensions.text = count.toString()
            }
        }
    }

    private fun updateBatteryCard() {
        val pm = requireContext().getSystemService(PowerManager::class.java)
        val ignoring = pm?.isIgnoringBatteryOptimizations(requireContext().packageName) ?: true
        binding.cardBattery.visibility = if (ignoring) View.GONE else View.VISIBLE
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:${requireContext().packageName}"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    private fun formatUptime(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
