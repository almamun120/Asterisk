package com.asterisk.voip.ui.gateways

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import com.asterisk.voip.data.model.Trunk
import com.asterisk.voip.databinding.DialogGatewayBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class GatewayDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogGatewayBinding? = null
    private val binding get() = _binding!!
    private val viewModel: GatewaysViewModel by viewModels({ requireParentFragment() })
    private var editing: Trunk? = null

    companion object {
        private const val ARG = "trunk"
        fun newInstance(t: Trunk?) = GatewayDialogFragment().apply {
            arguments = Bundle().apply { t?.let { putParcelable(ARG, it) } }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogGatewayBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("DEPRECATION")
        editing = arguments?.getParcelable(ARG)
        editing?.let { t ->
            binding.apply {
                tvTitle.text = "Edit Gateway"
                etName.setText(t.name); etHost.setText(t.host); etPort.setText(t.port.toString())
                etUsername.setText(t.username); etPassword.setText(t.password)
                etAuthUser.setText(t.authUser); etDomain.setText(t.domain); etOutboundProxy.setText(t.outboundProxy)
                switchRegister.isChecked = t.register
                etExpiry.setText(t.expiry.toString()); etKeepAlive.setText(t.keepAlive.toString())
                etCodecs.setText(t.codecs); etMaxChannels.setText(t.maxChannels.toString())
                etOutPrefixes.setText(t.outPrefixes); etStripDigits.setText(t.stripDigits.toString())
                etAddPrefix.setText(t.addPrefix); etCallerId.setText(t.callerId); etPriority.setText(t.priority.toString())
                etInboundTarget.setText(t.inboundTarget); etInboundMap.setText(t.inboundMap)
            }
        }
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener { if (validate()) { save(); dismiss() } }
    }

    private fun validate(): Boolean {
        var ok = true
        binding.apply {
            for ((til, et) in listOf(tilName to etName, tilHost to etHost, tilUsername to etUsername, tilPassword to etPassword)) {
                if (et.text.isNullOrBlank()) { til.error = "Required"; ok = false } else til.error = null
            }
        }
        return ok
    }

    private fun txt(e: com.google.android.material.textfield.TextInputEditText) = e.text?.toString()?.trim() ?: ""
    private fun num(e: com.google.android.material.textfield.TextInputEditText, def: Int) = txt(e).toIntOrNull() ?: def

    private fun save() {
        val b = binding
        val t = Trunk(
            id = editing?.id ?: 0,
            name = txt(b.etName),
            host = txt(b.etHost).removePrefix("sip:"),
            port = num(b.etPort, 5060).coerceIn(1, 65535),
            username = txt(b.etUsername),
            password = b.etPassword.text?.toString() ?: "",
            authUser = txt(b.etAuthUser),
            domain = txt(b.etDomain),
            outboundProxy = txt(b.etOutboundProxy),
            register = b.switchRegister.isChecked,
            expiry = num(b.etExpiry, 300).coerceIn(30, 3600),
            keepAlive = num(b.etKeepAlive, 25).coerceIn(0, 300),
            callerId = txt(b.etCallerId),
            outPrefixes = txt(b.etOutPrefixes).ifBlank { "*" },
            stripDigits = num(b.etStripDigits, 0).coerceAtLeast(0),
            addPrefix = txt(b.etAddPrefix),
            priority = num(b.etPriority, 10),
            codecs = txt(b.etCodecs),
            inboundTarget = txt(b.etInboundTarget),
            inboundMap = txt(b.etInboundMap),
            maxChannels = num(b.etMaxChannels, 0).coerceAtLeast(0),
            enabled = editing?.enabled ?: true,
            createdAt = editing?.createdAt ?: System.currentTimeMillis()
        )
        if (editing != null) viewModel.update(t) else viewModel.add(t)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
