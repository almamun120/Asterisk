package com.asterisk.voip.ui.extensions

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.asterisk.voip.data.model.Extension
import com.asterisk.voip.databinding.DialogExtensionBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class ExtensionDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogExtensionBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ExtensionsViewModel by viewModels({ requireParentFragment() })
    private var editingExtension: Extension? = null

    companion object {
        private const val ARG_EXTENSION = "extension"
        fun newInstance(extension: Extension?): ExtensionDialogFragment {
            return ExtensionDialogFragment().apply {
                arguments = Bundle().apply {
                    extension?.let { putParcelable(ARG_EXTENSION, it) }
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogExtensionBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("DEPRECATION")
        editingExtension = arguments?.getParcelable(ARG_EXTENSION)
        setupUI()
        setupListeners()
    }

    private fun setupUI() {
        editingExtension?.let { ext ->
            binding.apply {
                etExtensionNumber.setText(ext.extensionNumber)
                etName.setText(ext.name)
                etSecret.setText(ext.secret)
                etCallerId.setText(ext.callerID)
                etContext.setText(ext.context)
                etCodecs.setText(ext.codecs)
                switchNat.isChecked = ext.nat.isNotEmpty()
                switchQualify.isChecked = ext.qualify
            }
            binding.tvTitle.text = "Edit Extension"
        } ?: run {
            binding.tvTitle.text = "Add Extension"
        }
    }

    private fun setupListeners() {
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener {
            if (validateInput()) {
                saveExtension()
                dismiss()
            }
        }
        binding.btnGenerateSecret.setOnClickListener {
            binding.etSecret.setText(generateRandomSecret())
        }
    }

    private fun validateInput(): Boolean {
        var isValid = true
        binding.apply {
            if (etExtensionNumber.text.isNullOrBlank()) {
                tilExtensionNumber.error = "Required"
                isValid = false
            } else tilExtensionNumber.error = null

            if (etName.text.isNullOrBlank()) {
                tilName.error = "Required"
                isValid = false
            } else tilName.error = null

            if (etSecret.text.isNullOrBlank()) {
                tilSecret.error = "Required"
                isValid = false
            } else tilSecret.error = null
        }
        return isValid
    }

    private fun saveExtension() {
        val extension = Extension(
            id = editingExtension?.id ?: 0,
            extensionNumber = binding.etExtensionNumber.text.toString().trim(),
            name = binding.etName.text.toString().trim(),
            secret = binding.etSecret.text.toString().trim(),
            callerID = binding.etCallerId.text?.toString()?.trim() ?: binding.etName.text.toString().trim(),
            context = binding.etContext.text?.toString()?.trim() ?: "default",
            codecs = binding.etCodecs.text?.toString()?.trim() ?: "ulaw,alaw,gsm",
            nat = if (binding.switchNat.isChecked) "force_rport,comedia" else "",
            qualify = binding.switchQualify.isChecked,
            enabled = true
        )
        if (editingExtension != null) viewModel.updateExtension(extension)
        else viewModel.addExtension(extension)
    }

    private fun generateRandomSecret(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}