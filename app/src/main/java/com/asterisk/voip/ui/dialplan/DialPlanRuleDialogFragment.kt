// FILE: ./app/src/main/java/com/asterisk/voip/ui/dialplan/DialPlanRuleDialogFragment.kt
package com.asterisk.voip.ui.dialplan

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.fragment.app.viewModels
import com.asterisk.voip.R
import com.asterisk.voip.data.model.DialPlanAction
import com.asterisk.voip.data.model.DialPlanCondition
import com.asterisk.voip.data.model.DialPlanRule
import com.asterisk.voip.databinding.DialogDialplanRuleBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class DialPlanRuleDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogDialplanRuleBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DialPlanViewModel by viewModels({ requireParentFragment() })
    private var editingRule: DialPlanRule? = null

    companion object {
        private const val ARG_RULE = "rule"

        fun newInstance(rule: DialPlanRule?): DialPlanRuleDialogFragment {
            return DialPlanRuleDialogFragment().apply {
                arguments = Bundle().apply {
                    rule?.let { putParcelable(ARG_RULE, it) }
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogDialplanRuleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("DEPRECATION")
        editingRule = arguments?.getParcelable(ARG_RULE)
        setupSpinners()
        setupUI()
        setupListeners()
    }

    private fun setupSpinners() {
        // Condition Spinner
        val conditions = DialPlanCondition.values().map { formatCondition(it) }
        val conditionAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            conditions
        )
        conditionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerCondition.adapter = conditionAdapter

        // Action Spinner
        val actions = DialPlanAction.values().map { formatAction(it) }
        val actionAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            actions
        )
        actionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerAction.adapter = actionAdapter

        // Listen for action changes to show/hide action data field
        binding.spinnerAction.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateActionDataVisibility(DialPlanAction.values()[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })
    }

    private fun setupUI() {
        editingRule?.let { rule ->
            binding.apply {
                tvTitle.text = "Edit Dial Plan Rule"
                etRuleName.setText(rule.name)
                etRuleDescription.setText(rule.description)
                etPattern.setText(rule.pattern)
                spinnerCondition.setSelection(rule.condition.ordinal)
                spinnerAction.setSelection(rule.action.ordinal)
                etActionData.setText(rule.actionData)
                etPriority.setText(rule.priority.toString())
                etTimeout.setText(rule.timeout.toString())
                etMaxRetries.setText(rule.maxRetries.toString())
            }
        } ?: run {
            binding.tvTitle.text = "Add Dial Plan Rule"
            binding.etPriority.setText("10")
            binding.etTimeout.setText("30")
            binding.etMaxRetries.setText("3")
        }
    }

    private fun setupListeners() {
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener {
            if (validateInput()) {
                saveRule()
                dismiss()
            }
        }

        binding.btnPatternHelp.setOnClickListener {
            showPatternHelp()
        }
    }

    private fun validateInput(): Boolean {
        var isValid = true

        binding.apply {
            if (etRuleName.text.isNullOrBlank()) {
                tilRuleName.error = "Required"
                isValid = false
            } else {
                tilRuleName.error = null
            }

            if (etPattern.text.isNullOrBlank()) {
                tilPattern.error = "Required"
                isValid = false
            } else {
                tilPattern.error = null
            }

            if (etPriority.text.isNullOrBlank()) {
                tilPriority.error = "Required"
                isValid = false
            } else {
                tilPriority.error = null
            }
        }

        return isValid
    }

    private fun saveRule() {
        val rule = DialPlanRule(
            id = editingRule?.id ?: 0,
            name = binding.etRuleName.text.toString().trim(),
            description = binding.etRuleDescription.text.toString().trim(),
            pattern = binding.etPattern.text.toString().trim(),
            condition = DialPlanCondition.values()[binding.spinnerCondition.selectedItemPosition],
            action = DialPlanAction.values()[binding.spinnerAction.selectedItemPosition],
            actionData = binding.etActionData.text.toString().trim(),
            priority = binding.etPriority.text.toString().toIntOrNull() ?: 10,
            timeout = binding.etTimeout.text.toString().toIntOrNull() ?: 30,
            maxRetries = binding.etMaxRetries.text.toString().toIntOrNull() ?: 3,
            enabled = true
        )

        if (editingRule != null) {
            viewModel.updateRule(rule)
        } else {
            viewModel.addRule(rule)
        }
    }

    private fun updateActionDataVisibility(action: DialPlanAction) {
        binding.tilActionData.hint = when (action) {
            DialPlanAction.PLAY_ANNOUNCEMENT -> "Voice Prompt ID"
            DialPlanAction.FORWARD_TO_EXTENSION -> "Extension Number"
            DialPlanAction.FORWARD_TO_QUEUE -> "Queue ID"
            DialPlanAction.FORWARD_TO_IVR -> "IVR Menu ID"
            DialPlanAction.CUSTOM -> "Custom Action Data"
            else -> "Action Data (Optional)"
        }

        binding.tilActionData.visibility = when (action) {
            DialPlanAction.PLAY_ANNOUNCEMENT,
            DialPlanAction.FORWARD_TO_EXTENSION,
            DialPlanAction.FORWARD_TO_QUEUE,
            DialPlanAction.FORWARD_TO_IVR,
            DialPlanAction.CUSTOM -> View.VISIBLE
            else -> View.GONE
        }
    }

    private fun showPatternHelp() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("Pattern Help")
            .setMessage("""
                Pattern Examples:
                
                * = Match all extensions
                1234 = Exact match for extension 1234
                1XXX = Match 1000-1999
                [1-5]XX = Match 100-599
                _X. = Match any extension starting with any digit
                
                X = Any digit (0-9)
                [abc] = Any of a, b, or c
                [1-5] = Any digit from 1 to 5
            """.trimIndent())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun formatCondition(condition: DialPlanCondition): String {
        return when (condition) {
            DialPlanCondition.OFFLINE -> "Extension Offline"
            DialPlanCondition.BUSY -> "Extension Busy"
            DialPlanCondition.NO_ANSWER -> "No Answer"
            DialPlanCondition.REJECTED -> "Call Rejected"
            DialPlanCondition.NETWORK_ERROR -> "Network Error"
            DialPlanCondition.INVALID_NUMBER -> "Invalid Number"
            DialPlanCondition.FORBIDDEN -> "Forbidden"
            DialPlanCondition.TIMEOUT -> "Timeout"
            DialPlanCondition.UNAVAILABLE -> "Unavailable"
            DialPlanCondition.DO_NOT_DISTURB -> "Do Not Disturb"
            DialPlanCondition.CALL_FORWARDING -> "Call Forwarding"
            DialPlanCondition.AFTER_HOURS -> "After Hours"
            DialPlanCondition.ALWAYS -> "Always"
        }
    }

    private fun formatAction(action: DialPlanAction): String {
        return when (action) {
            DialPlanAction.PLAY_ANNOUNCEMENT -> "Play Announcement"
            DialPlanAction.VOICEMAIL -> "Send to Voicemail"
            DialPlanAction.FORWARD_TO_EXTENSION -> "Forward to Extension"
            DialPlanAction.FORWARD_TO_QUEUE -> "Forward to Queue"
            DialPlanAction.FORWARD_TO_IVR -> "Forward to IVR"
            DialPlanAction.HANGUP -> "Hang Up"
            DialPlanAction.CALLBACK -> "Schedule Callback"
            DialPlanAction.RETRY -> "Retry"
            DialPlanAction.CUSTOM -> "Custom Action"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}