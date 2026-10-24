// FILE: ./app/src/main/java/com/asterisk/voip/ui/dialplan/DialPlanFragment.kt
package com.asterisk.voip.ui.dialplan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.asterisk.voip.data.model.DialPlanRule
import com.asterisk.voip.data.model.VoicePrompt
import com.asterisk.voip.databinding.FragmentDialplanBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class DialPlanFragment : Fragment() {

    private var _binding: FragmentDialplanBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DialPlanViewModel by viewModels()

    private lateinit var rulesAdapter: DialPlanRulesAdapter
    private lateinit var promptsAdapter: VoicePromptsAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDialplanBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupTabs()
        setupRecyclerViews()
        setupFabs()
        observeViewModel()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Rules"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Voice Prompts"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                when (tab?.position) {
                    0 -> showRulesView()
                    1 -> showPromptsView()
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
    }

    private fun setupRecyclerViews() {
        // Rules RecyclerView
        rulesAdapter = DialPlanRulesAdapter(
            onEditClick = { showRuleDialog(it) },
            onDeleteClick = { confirmDeleteRule(it) },
            onToggleClick = { rule, enabled -> viewModel.toggleRule(rule, enabled) }
        )
        binding.recyclerRules.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = rulesAdapter
        }

        // Prompts RecyclerView
        promptsAdapter = VoicePromptsAdapter(
            onPlayClick = { viewModel.playPrompt(it) },
            onEditClick = { showPromptDialog(it) },
            onDeleteClick = { confirmDeletePrompt(it) },
            onToggleClick = { prompt, enabled -> viewModel.togglePrompt(prompt, enabled) }
        )
        binding.recyclerPrompts.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = promptsAdapter
        }
    }

    private fun setupFabs() {
        binding.fabAddRule.setOnClickListener { showRuleDialog(null) }
        binding.fabAddPrompt.setOnClickListener { showPromptDialog(null) }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.dialPlanRules.collectLatest { rules ->
                rulesAdapter.submitList(rules)
                binding.tvEmptyRules.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.voicePrompts.collectLatest { prompts ->
                promptsAdapter.submitList(prompts)
                binding.tvEmptyPrompts.visibility = if (prompts.isEmpty()) View.VISIBLE else View.GONE
            }
        }
    }

    private fun showRulesView() {
        binding.recyclerRules.visibility = View.VISIBLE
        binding.recyclerPrompts.visibility = View.GONE
        binding.fabAddRule.show()
        binding.fabAddPrompt.hide()
        binding.tvEmptyRules.visibility = if (rulesAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun showPromptsView() {
        binding.recyclerRules.visibility = View.GONE
        binding.recyclerPrompts.visibility = View.VISIBLE
        binding.fabAddRule.hide()
        binding.fabAddPrompt.show()
        binding.tvEmptyPrompts.visibility = if (promptsAdapter.itemCount == 0) View.VISIBLE else View.GONE
    }

    private fun showRuleDialog(rule: DialPlanRule?) {
        val dialog = DialPlanRuleDialogFragment.newInstance(rule)
        dialog.show(childFragmentManager, "rule_dialog")
    }

    private fun showPromptDialog(prompt: VoicePrompt?) {
        val dialog = VoicePromptDialogFragment.newInstance(prompt)
        dialog.show(childFragmentManager, "prompt_dialog")
    }

    private fun confirmDeleteRule(rule: DialPlanRule) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Rule")
            .setMessage("Delete dial plan rule '${rule.name}'?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteRule(rule) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeletePrompt(prompt: VoicePrompt) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Delete Voice Prompt")
            .setMessage("Delete voice prompt '${prompt.name}'?")
            .setPositiveButton("Delete") { _, _ -> viewModel.deletePrompt(prompt) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}