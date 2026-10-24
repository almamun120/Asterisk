package com.asterisk.voip.ui.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.asterisk.voip.data.model.Extension
import com.asterisk.voip.databinding.FragmentExtensionsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ExtensionsFragment : Fragment() {

    private var _binding: FragmentExtensionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExtensionsViewModel by viewModels()
    private lateinit var adapter: ExtensionsAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentExtensionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ExtensionsAdapter(
            onEditClick = { showExtensionDialog(it) },
            onDeleteClick = { confirmDelete(it) },
            onToggleClick = { ext, enabled -> viewModel.toggleExtension(ext, enabled) }
        )
        binding.recyclerExtensions.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerExtensions.adapter = adapter
    }

    private fun setupFab() {
        binding.fabAddExtension.setOnClickListener { showExtensionDialog(null) }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.onlineExtensions.collectLatest { adapter.setOnline(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.extensions.collectLatest { extensions ->
                adapter.submitList(extensions)
                binding.tvEmpty.visibility = if (extensions.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showExtensionDialog(extension: Extension?) {
        val dialog = ExtensionDialogFragment.newInstance(extension)
        dialog.show(childFragmentManager, "extension_dialog")
    }

    private fun confirmDelete(extension: Extension) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Extension")
            .setMessage("Delete extension ${extension.extensionNumber}?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteExtension(extension) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}