package com.asterisk.voip.ui.gateways

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.asterisk.voip.data.model.Trunk
import com.asterisk.voip.databinding.FragmentGatewaysBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class GatewaysFragment : Fragment() {

    private var _binding: FragmentGatewaysBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GatewaysViewModel by viewModels()
    private lateinit var adapter: GatewaysAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentGatewaysBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        adapter = GatewaysAdapter(
            onEdit = { showDialog(it) },
            onDelete = { confirmDelete(it) },
            onToggle = { t, on -> viewModel.toggle(t, on) },
            onRetry = { viewModel.retry(it) }
        )
        binding.recyclerGateways.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerGateways.adapter = adapter
        binding.fabAddGateway.setOnClickListener { showDialog(null) }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.trunks.collectLatest { list ->
                adapter.submitList(list)
                binding.tvEmpty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            combine(viewModel.status, viewModel.serverRunning) { s, r -> s to r }
                .collectLatest { (s, r) -> adapter.setStatus(s, r) }
        }
    }

    private fun showDialog(t: Trunk?) {
        GatewayDialogFragment.newInstance(t).show(childFragmentManager, "gateway_dialog")
    }

    private fun confirmDelete(t: Trunk) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete gateway")
            .setMessage("Log out of and delete '${t.name}'?")
            .setPositiveButton("Delete") { _, _ -> viewModel.delete(t) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
