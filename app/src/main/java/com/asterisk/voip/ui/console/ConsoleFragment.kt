package com.asterisk.voip.ui.console

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.asterisk.voip.databinding.FragmentConsoleBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConsoleFragment : Fragment() {

    private var _binding: FragmentConsoleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ConsoleViewModel by viewModels()
    private lateinit var adapter: ConsoleAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentConsoleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupControls()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ConsoleAdapter()
        binding.recyclerLogs.layoutManager = LinearLayoutManager(requireContext()).apply { stackFromEnd = true }
        binding.recyclerLogs.adapter = adapter
    }

    private fun setupControls() {
        binding.btnClear.setOnClickListener { viewModel.clearLogs() }
        binding.btnShare.setOnClickListener {
            val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
            val text = viewModel.consoleLogs.value.joinToString("\n") {
                "${fmt.format(java.util.Date(it.timestamp))} [${it.level.name[0]}] ${it.tag}: ${it.message}"
            }
            startActivity(android.content.Intent.createChooser(
                android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text.ifEmpty { "(no logs)" }),
                "Share console log"))
        }
        binding.switchAutoScroll.setOnCheckedChangeListener { _, isChecked -> viewModel.setAutoScroll(isChecked) }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.consoleLogs.collectLatest { logs ->
                adapter.submitList(logs)
                if (viewModel.autoScroll.value && logs.isNotEmpty()) {
                    binding.recyclerLogs.scrollToPosition(logs.size - 1)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}