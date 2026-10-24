package com.asterisk.voip.ui.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.asterisk.voip.R
import com.asterisk.voip.data.model.ServerConfig
import com.asterisk.voip.data.model.StunTurnConfig
import com.asterisk.voip.databinding.FragmentSettingsBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: SettingsViewModel by viewModels()

    // Available codecs
    private val availableAudioCodecs = listOf(
        "opus" to "Opus (Recommended)",
        "g722" to "G.722 (HD Audio)",
        "pcmu" to "G.711 μ-law (PCMU)",
        "pcma" to "G.711 A-law (PCMA)",
        "g729" to "G.729 (Low Bandwidth)",
        "gsm" to "GSM",
        "ilbc" to "iLBC",
        "speex" to "Speex",
        "amr" to "AMR",
        "amr-wb" to "AMR-WB"
    )

    private val availableVideoCodecs = listOf(
        "h264" to "H.264 (AVC)",
        "h265" to "H.265 (HEVC)",
        "vp8" to "VP8",
        "vp9" to "VP9",
        "h263" to "H.263",
        "h263p" to "H.263+"
    )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSpinners()
        setupListeners()
        observeViewModel()
    }

    private fun setupSpinners() {
        // DTMF Mode
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.dtmf_modes,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerDtmfMode.adapter = adapter
        }

        // SIP Transport
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.sip_transports,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerSipTransport.adapter = adapter
        }

        // Video Resolution
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.video_resolutions,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerVideoResolution.adapter = adapter
        }

        // Media routing + theme
        ArrayAdapter.createFromResource(requireContext(), R.array.media_modes, android.R.layout.simple_spinner_item).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerMediaMode.adapter = it
        }
        ArrayAdapter.createFromResource(requireContext(), R.array.theme_modes, android.R.layout.simple_spinner_item).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerTheme.adapter = it
        }
        binding.spinnerTheme.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val mode = listOf("system", "light", "dark").getOrElse(pos) { "system" }
                if (mode != viewModel.themeMode.value) viewModel.setThemeMode(mode)
            }
            override fun onNothingSelected(p: android.widget.AdapterView<*>?) {}
        }

        // Recording Format
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.recording_formats,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerRecordingFormat.adapter = adapter
        }

        // Queue Strategy
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.queue_strategies,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerQueueStrategy.adapter = adapter
        }

        // Log Level
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.log_levels,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.spinnerLogLevel.adapter = adapter
        }
    }

    private fun setupListeners() {
        // General
        binding.switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setStartOnBoot(isChecked)
        }

        // Codecs
        binding.btnConfigureAudioCodecs.setOnClickListener {
            showAudioCodecDialog()
        }

        binding.btnConfigureVideoCodecs.setOnClickListener {
            showVideoCodecDialog()
        }

        // Video toggle
        binding.switchVideo.setOnCheckedChangeListener { _, isChecked ->
            updateVideoFieldsVisibility(isChecked)
        }

        // Security
        binding.switchTls.setOnCheckedChangeListener { _, isChecked ->
            updateTlsFieldsVisibility(isChecked)
        }

        binding.switchSrtp.setOnCheckedChangeListener { _, isChecked ->
            binding.switchRequireSrtp.isEnabled = isChecked
        }

        // IVR
        binding.switchIvr.setOnCheckedChangeListener { _, isChecked ->
            updateIvrFieldsVisibility(isChecked)
        }

        binding.btnConfigureIvr.setOnClickListener {
            // Navigate to IVR configuration screen
            Toast.makeText(requireContext(), "IVR Configuration", Toast.LENGTH_SHORT).show()
        }

        // Call Queues
        binding.switchCallQueues.setOnCheckedChangeListener { _, isChecked ->
            updateCallQueueFieldsVisibility(isChecked)
        }

        binding.btnConfigureQueues.setOnClickListener {
            // Navigate to queue configuration screen
            Toast.makeText(requireContext(), "Queue Configuration", Toast.LENGTH_SHORT).show()
        }

        // STUN/TURN
        binding.switchStun.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setStunEnabled(isChecked)
            updateStunFieldsVisibility(isChecked)
        }

        binding.switchTurn.setOnCheckedChangeListener { _, isChecked ->
            viewModel.setTurnEnabled(isChecked)
            updateTurnFieldsVisibility(isChecked)
        }

        // Buttons
        binding.btnSaveConfig.setOnClickListener {
            saveConfiguration()
        }

        binding.btnResetConfig.setOnClickListener {
            showResetConfirmation()
        }

        binding.btnExportConfig.setOnClickListener {
            exportConfiguration()
        }

        binding.btnImportConfig.setOnClickListener {
            importConfiguration()
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.startOnBoot.collectLatest { enabled ->
                binding.switchStartOnBoot.isChecked = enabled
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.themeMode.collectLatest { m ->
                binding.spinnerTheme.setSelection(listOf("system", "light", "dark").indexOf(m).coerceAtLeast(0))
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.serverConfig.collectLatest { config ->
                populateServerConfig(config)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.stunTurnConfig.collectLatest { config ->
                populateStunTurnConfig(config)
            }
        }
    }

    private fun populateServerConfig(config: ServerConfig) {
        binding.apply {
            // Network
            etBindAddress.setText(config.bindAddress)
            etSipPort.setText(config.sipPort.toString())
            etRtpStartPort.setText(config.rtpStartPort.toString())
            etRtpEndPort.setText(config.rtpEndPort.toString())
            etMaxCalls.setText(config.maxCalls.toString())

            // Media / registration
            spinnerMediaMode.setSelection(listOf("auto", "relay", "direct").indexOf(config.mediaMode).coerceAtLeast(0))
            etRegisterExpiry.setText(config.registerExpiry.toString())
            etRelayIdle.setText(config.relayIdleTimeout.toString())

            // SIP Transport
            spinnerSipTransport.setSelection(getSipTransportIndex(config.sipTransport))
            switchTls.isChecked = config.tlsEnabled
            etTlsCertPath.setText(config.tlsCertPath)
            etTlsKeyPath.setText(config.tlsKeyPath)
            updateTlsFieldsVisibility(config.tlsEnabled)

            // NAT
            switchNat.isChecked = config.natEnabled
            etExternalIp.setText(config.externalIP)
            etLocalNet.setText(config.localNet)

            // Audio Codecs
            tvSelectedAudioCodecs.text = config.audioCodecs.replace(",", ", ")

            // Video
            switchVideo.isChecked = config.videoEnabled
            tvSelectedVideoCodecs.text = config.videoCodecs.replace(",", ", ")
            spinnerVideoResolution.setSelection(getVideoResolutionIndex(config.videoResolution))
            etVideoBitrate.setText(config.videoBitrate.toString())
            etVideoFrameRate.setText(config.videoFrameRate.toString())
            updateVideoFieldsVisibility(config.videoEnabled)

            // DTMF
            spinnerDtmfMode.setSelection(getDtmfModeIndex(config.dtmfMode))

            // Session Timers
            switchSessionTimers.isChecked = config.sessionTimers
            etSessionExpires.setText(config.sessionExpires.toString())
            etSessionMinSe.setText(config.sessionMinSE.toString())

            // QoS
            switchQos.isChecked = config.qosEnabled
            etDscp.setText(config.dscp.toString())
            etTos.setText(config.tos.toString())

            // Jitter Buffer
            switchJitterBuffer.isChecked = config.jitterBufferEnabled
            etJitterMin.setText(config.jitterBufferMin.toString())
            etJitterMax.setText(config.jitterBufferMax.toString())
            etJitterTarget.setText(config.jitterBufferTarget.toString())

            // Security
            switchSrtp.isChecked = config.srtpEnabled
            switchRequireSrtp.isChecked = config.requireSrtp
            switchRequireSrtp.isEnabled = config.srtpEnabled
            etIpWhitelist.setText(config.ipWhitelist)
            etIpBlacklist.setText(config.ipBlacklist)
            etMaxAuthAttempts.setText(config.maxAuthAttempts.toString())
            etBanDuration.setText(config.banDuration.toString())

            // Features
            switchAllowGuest.isChecked = config.allowGuest

            // Call Features
            switchCallWaiting.isChecked = config.callWaiting
            switchCallForwarding.isChecked = config.callForwarding
            switchCallTransfer.isChecked = config.callTransfer
            switchCallParking.isChecked = config.callParking
            switchRecordCalls.isChecked = config.callRecording
            spinnerRecordingFormat.setSelection(getRecordingFormatIndex(config.recordingFormat))

            // Music on Hold
            switchMusicOnHold.isChecked = config.musicOnHold
            etMohClass.setText(config.mohClass)

            // Voicemail
            switchVoicemail.isChecked = config.voicemailEnabled
            etVoicemailMaxMessage.setText(config.voicemailMaxMessage.toString())
            etVoicemailMaxSeconds.setText(config.voicemailMaxSeconds.toString())
            etVoicemailMinSeconds.setText(config.voicemailMinSeconds.toString())
            switchVoicemailEmail.isChecked = config.voicemailEmail
            switchVoicemailAttach.isChecked = config.voicemailAttach

            // IVR
            switchIvr.isChecked = config.ivrEnabled
            etIvrTimeout.setText(config.ivrTimeout.toString())
            etIvrMaxRetries.setText(config.ivrMaxRetries.toString())
            updateIvrFieldsVisibility(config.ivrEnabled)

            // Call Queues
            switchCallQueues.isChecked = config.callQueuesEnabled
            spinnerQueueStrategy.setSelection(getQueueStrategyIndex(config.queueStrategy))
            etQueueTimeout.setText(config.queueTimeout.toString())
            etQueueMaxLen.setText(config.queueMaxLen.toString())
            switchQueueAnnouncePosition.isChecked = config.queueAnnouncePosition
            updateCallQueueFieldsVisibility(config.callQueuesEnabled)

            // CDR
            switchCdr.isChecked = config.cdrEnabled

            // Logging
            spinnerLogLevel.setSelection(getLogLevelIndex(config.logLevel))
            switchSipDebug.isChecked = config.sipDebug
            switchRtpDebug.isChecked = config.rtpDebug

            // Advanced
            etMaxRetransmits.setText(config.maxRetransmits.toString())
            etRegisterTimeout.setText(config.registerTimeout.toString())
            etQualifyFrequency.setText(config.qualifyFrequency.toString())
        }
    }

    private fun populateStunTurnConfig(config: StunTurnConfig) {
        binding.apply {
            switchStun.isChecked = config.stunEnabled
            etStunPort.setText(config.stunPort.toString())
            updateStunFieldsVisibility(config.stunEnabled)

            switchTurn.isChecked = config.turnEnabled
            etTurnPort.setText(config.turnPort.toString())
            etTurnUsername.setText(config.turnUsername)
            etTurnPassword.setText(config.turnPassword)
            etTurnRealm.setText(config.turnRealm)
            etTurnMinPort.setText(config.turnMinPort.toString())
            etTurnMaxPort.setText(config.turnMaxPort.toString())
            updateTurnFieldsVisibility(config.turnEnabled)
        }
    }

    private fun showAudioCodecDialog() {
        val currentCodecs = binding.tvSelectedAudioCodecs.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val codecNames = availableAudioCodecs.map { it.second }.toTypedArray()
        val codecIds = availableAudioCodecs.map { it.first }
        val checkedItems = BooleanArray(availableAudioCodecs.size) { index ->
            currentCodecs.contains(codecIds[index])
        }
        val selectedCodecs = currentCodecs.toMutableList()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Audio Codecs")
            .setMultiChoiceItems(codecNames, checkedItems) { _, which, isChecked ->
                val codecId = codecIds[which]
                if (isChecked) {
                    if (!selectedCodecs.contains(codecId)) {
                        selectedCodecs.add(codecId)
                    }
                } else {
                    selectedCodecs.remove(codecId)
                }
            }
            .setPositiveButton("OK") { _, _ ->
                binding.tvSelectedAudioCodecs.text = selectedCodecs.joinToString(", ")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Default") { _, _ ->
                binding.tvSelectedAudioCodecs.text = "opus, g722, pcmu, pcma, gsm"
            }
            .show()
    }

    private fun showVideoCodecDialog() {
        val currentCodecs = binding.tvSelectedVideoCodecs.text.toString()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }

        val codecNames = availableVideoCodecs.map { it.second }.toTypedArray()
        val codecIds = availableVideoCodecs.map { it.first }
        val checkedItems = BooleanArray(availableVideoCodecs.size) { index ->
            currentCodecs.contains(codecIds[index])
        }
        val selectedCodecs = currentCodecs.toMutableList()

        AlertDialog.Builder(requireContext())
            .setTitle("Select Video Codecs")
            .setMultiChoiceItems(codecNames, checkedItems) { _, which, isChecked ->
                val codecId = codecIds[which]
                if (isChecked) {
                    if (!selectedCodecs.contains(codecId)) {
                        selectedCodecs.add(codecId)
                    }
                } else {
                    selectedCodecs.remove(codecId)
                }
            }
            .setPositiveButton("OK") { _, _ ->
                binding.tvSelectedVideoCodecs.text = selectedCodecs.joinToString(", ")
            }
            .setNegativeButton("Cancel", null)
            .setNeutralButton("Default") { _, _ ->
                binding.tvSelectedVideoCodecs.text = "vp8, h264, vp9"
            }
            .show()
    }

    private fun saveConfiguration() {
        try {
            val serverConfig = buildServerConfig()
            val stunTurnConfig = buildStunTurnConfig()

            viewModel.saveServerConfig(serverConfig)
            viewModel.saveStunTurnConfig(stunTurnConfig)

            Toast.makeText(requireContext(), "✓ Configuration saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildServerConfig(): ServerConfig {
        return binding.run {
            ServerConfig(
                // Network
                bindAddress = etBindAddress.text.toString().trim().ifEmpty { "0.0.0.0" },
                sipPort = etSipPort.text.toString().toIntOrNull() ?: 5060,
                rtpStartPort = etRtpStartPort.text.toString().toIntOrNull() ?: 10000,
                rtpEndPort = etRtpEndPort.text.toString().toIntOrNull() ?: 20000,
                maxCalls = etMaxCalls.text.toString().toIntOrNull() ?: 100,

                // Media / registration
                mediaMode = listOf("auto", "relay", "direct").getOrElse(spinnerMediaMode.selectedItemPosition) { "auto" },
                registerExpiry = (etRegisterExpiry.text.toString().toIntOrNull() ?: 120).coerceIn(30, 3600),
                relayIdleTimeout = (etRelayIdle.text.toString().toIntOrNull() ?: 120).coerceIn(0, 3600),

                // SIP Transport
                sipTransport = getSipTransportFromSpinner(),
                tlsEnabled = switchTls.isChecked,
                tlsCertPath = etTlsCertPath.text.toString().trim(),
                tlsKeyPath = etTlsKeyPath.text.toString().trim(),

                // NAT
                natEnabled = switchNat.isChecked,
                externalIP = etExternalIp.text.toString().trim(),
                localNet = etLocalNet.text.toString().trim().ifEmpty { "192.168.0.0/16" },

                // Audio Codecs
                audioCodecs = tvSelectedAudioCodecs.text.toString().replace(", ", ","),

                // Video
                videoEnabled = switchVideo.isChecked,
                videoCodecs = tvSelectedVideoCodecs.text.toString().replace(", ", ","),
                videoBitrate = etVideoBitrate.text.toString().toIntOrNull() ?: 512,
                videoResolution = getVideoResolutionFromSpinner(),
                videoFrameRate = etVideoFrameRate.text.toString().toIntOrNull() ?: 30,

                // DTMF
                dtmfMode = getDtmfModeFromSpinner(),

                // Session Timers
                sessionTimers = switchSessionTimers.isChecked,
                sessionExpires = etSessionExpires.text.toString().toIntOrNull() ?: 1800,
                sessionMinSE = etSessionMinSe.text.toString().toIntOrNull() ?: 90,

                // QoS
                qosEnabled = switchQos.isChecked,
                dscp = etDscp.text.toString().toIntOrNull() ?: 46,
                tos = etTos.text.toString().toIntOrNull() ?: 0xB8,

                // Jitter Buffer
                jitterBufferEnabled = switchJitterBuffer.isChecked,
                jitterBufferMin = etJitterMin.text.toString().toIntOrNull() ?: 40,
                jitterBufferMax = etJitterMax.text.toString().toIntOrNull() ?: 200,
                jitterBufferTarget = etJitterTarget.text.toString().toIntOrNull() ?: 60,

                // Security
                srtpEnabled = switchSrtp.isChecked,
                requireSrtp = switchRequireSrtp.isChecked,
                ipWhitelist = etIpWhitelist.text.toString().trim(),
                ipBlacklist = etIpBlacklist.text.toString().trim(),
                maxAuthAttempts = etMaxAuthAttempts.text.toString().toIntOrNull() ?: 3,
                banDuration = etBanDuration.text.toString().toIntOrNull() ?: 3600,

                // Features
                allowGuest = switchAllowGuest.isChecked,

                // Call Features
                callWaiting = switchCallWaiting.isChecked,
                callForwarding = switchCallForwarding.isChecked,
                callTransfer = switchCallTransfer.isChecked,
                callParking = switchCallParking.isChecked,
                callRecording = switchRecordCalls.isChecked,
                recordingFormat = getRecordingFormatFromSpinner(),

                // Music on Hold
                musicOnHold = switchMusicOnHold.isChecked,
                mohClass = etMohClass.text.toString().trim(),

                // Voicemail
                voicemailEnabled = switchVoicemail.isChecked,
                voicemailMaxMessage = etVoicemailMaxMessage.text.toString().toIntOrNull() ?: 100,
                voicemailMaxSeconds = etVoicemailMaxSeconds.text.toString().toIntOrNull() ?: 300,
                voicemailMinSeconds = etVoicemailMinSeconds.text.toString().toIntOrNull() ?: 3,
                voicemailEmail = switchVoicemailEmail.isChecked,
                voicemailAttach = switchVoicemailAttach.isChecked,

                // IVR
                ivrEnabled = switchIvr.isChecked,
                ivrTimeout = etIvrTimeout.text.toString().toIntOrNull() ?: 10,
                ivrMaxRetries = etIvrMaxRetries.text.toString().toIntOrNull() ?: 3,

                // Call Queues
                callQueuesEnabled = switchCallQueues.isChecked,
                queueStrategy = getQueueStrategyFromSpinner(),
                queueTimeout = etQueueTimeout.text.toString().toIntOrNull() ?: 300,
                queueMaxLen = etQueueMaxLen.text.toString().toIntOrNull() ?: 0,
                queueAnnouncePosition = switchQueueAnnouncePosition.isChecked,

                // CDR
                cdrEnabled = switchCdr.isChecked,

                // Logging
                logLevel = getLogLevelFromSpinner(),
                sipDebug = switchSipDebug.isChecked,
                rtpDebug = switchRtpDebug.isChecked,

                // Advanced
                maxRetransmits = etMaxRetransmits.text.toString().toIntOrNull() ?: 10,
                registerTimeout = etRegisterTimeout.text.toString().toIntOrNull() ?: 3600,
                qualifyFrequency = etQualifyFrequency.text.toString().toIntOrNull() ?: 60
            )
        }
    }

    private fun buildStunTurnConfig(): StunTurnConfig {
        return binding.run {
            StunTurnConfig(
                stunEnabled = switchStun.isChecked,
                stunPort = etStunPort.text.toString().toIntOrNull() ?: 3478,
                turnEnabled = switchTurn.isChecked,
                turnPort = etTurnPort.text.toString().toIntOrNull() ?: 3478,
                turnUsername = etTurnUsername.text.toString().trim().ifEmpty { "asterisk" },
                turnPassword = etTurnPassword.text.toString().trim().ifEmpty { "turnpass" },
                turnRealm = etTurnRealm.text.toString().trim().ifEmpty { "asterisk.local" },
                turnMinPort = etTurnMinPort.text.toString().toIntOrNull() ?: 49152,
                turnMaxPort = etTurnMaxPort.text.toString().toIntOrNull() ?: 65535
            )
        }
    }

    // Helper methods for spinners
    private fun getSipTransportIndex(transport: String): Int = when (transport) {
        "UDP" -> 0
        "TCP" -> 1
        "UDP,TCP" -> 2
        "TLS" -> 3
        else -> 2
    }

    private fun getSipTransportFromSpinner(): String = when (binding.spinnerSipTransport.selectedItemPosition) {
        0 -> "UDP"
        1 -> "TCP"
        2 -> "UDP,TCP"
        3 -> "TLS"
        else -> "UDP,TCP"
    }

    private fun getDtmfModeIndex(mode: String): Int = when (mode) {
        "rfc2833" -> 0
        "inband" -> 1
        "sip_info" -> 2
        "auto" -> 3
        else -> 0
    }

    private fun getDtmfModeFromSpinner(): String = when (binding.spinnerDtmfMode.selectedItemPosition) {
        0 -> "rfc2833"
        1 -> "inband"
        2 -> "sip_info"
        3 -> "auto"
        else -> "rfc2833"
    }

    private fun getVideoResolutionIndex(resolution: String): Int = when (resolution) {
        "1080p" -> 0
        "720p" -> 1
        "480p" -> 2
        "360p" -> 3
        else -> 1
    }

    private fun getVideoResolutionFromSpinner(): String = when (binding.spinnerVideoResolution.selectedItemPosition) {
        0 -> "1080p"
        1 -> "720p"
        2 -> "480p"
        3 -> "360p"
        else -> "720p"
    }

    private fun getRecordingFormatIndex(format: String): Int = when (format) {
        "wav" -> 0
        "mp3" -> 1
        "gsm" -> 2
        else -> 0
    }

    private fun getRecordingFormatFromSpinner(): String = when (binding.spinnerRecordingFormat.selectedItemPosition) {
        0 -> "wav"
        1 -> "mp3"
        2 -> "gsm"
        else -> "wav"
    }

    private fun getQueueStrategyIndex(strategy: String): Int = when (strategy) {
        "ringall" -> 0
        "leastrecent" -> 1
        "fewestcalls" -> 2
        "random" -> 3
        "rrmemory" -> 4
        else -> 0
    }

    private fun getQueueStrategyFromSpinner(): String = when (binding.spinnerQueueStrategy.selectedItemPosition) {
        0 -> "ringall"
        1 -> "leastrecent"
        2 -> "fewestcalls"
        3 -> "random"
        4 -> "rrmemory"
        else -> "ringall"
    }

    private fun getLogLevelIndex(level: String): Int = when (level) {
        "debug" -> 0
        "info" -> 1
        "warning" -> 2
        "error" -> 3
        else -> 1
    }

    private fun getLogLevelFromSpinner(): String = when (binding.spinnerLogLevel.selectedItemPosition) {
        0 -> "debug"
        1 -> "info"
        2 -> "warning"
        3 -> "error"
        else -> "info"
    }

    // Visibility helpers
    private fun updateVideoFieldsVisibility(enabled: Boolean) {
        binding.apply {
            val visibility = if (enabled) View.VISIBLE else View.GONE
            layoutVideoCodecs.visibility = visibility
            layoutVideoSettings.visibility = visibility
        }
    }

    private fun updateTlsFieldsVisibility(enabled: Boolean) {
        binding.apply {
            val visibility = if (enabled) View.VISIBLE else View.GONE
            tilTlsCertPath.visibility = visibility
            tilTlsKeyPath.visibility = visibility
        }
    }

    private fun updateStunFieldsVisibility(enabled: Boolean) {
        binding.tilStunPort.visibility = if (enabled) View.VISIBLE else View.GONE
    }

    private fun updateTurnFieldsVisibility(enabled: Boolean) {
        binding.apply {
            val visibility = if (enabled) View.VISIBLE else View.GONE
            tilTurnPort.visibility = visibility
            tilTurnUsername.visibility = visibility
            tilTurnPassword.visibility = visibility
            tilTurnRealm.visibility = visibility
            layoutTurnPorts.visibility = visibility
        }
    }

    private fun updateIvrFieldsVisibility(enabled: Boolean) {
        binding.apply {
            val visibility = if (enabled) View.VISIBLE else View.GONE
            layoutIvrSettings.visibility = visibility
            btnConfigureIvr.visibility = visibility
        }
    }

    private fun updateCallQueueFieldsVisibility(enabled: Boolean) {
        binding.apply {
            val visibility = if (enabled) View.VISIBLE else View.GONE
            layoutQueueSettings.visibility = visibility
            btnConfigureQueues.visibility = visibility
        }
    }

    private fun showResetConfirmation() {
        AlertDialog.Builder(requireContext())
            .setTitle("Reset Configuration")
            .setMessage("This will reset all settings to default values. Continue?")
            .setPositiveButton("Reset") { _, _ ->
                viewModel.resetConfig()
                Toast.makeText(requireContext(), "Configuration reset", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportConfiguration() {
        // TODO: Implement config export to JSON file
        Toast.makeText(requireContext(), "Export feature coming soon", Toast.LENGTH_SHORT).show()
    }

    private fun importConfiguration() {
        // TODO: Implement config import from JSON file
        Toast.makeText(requireContext(), "Import feature coming soon", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}