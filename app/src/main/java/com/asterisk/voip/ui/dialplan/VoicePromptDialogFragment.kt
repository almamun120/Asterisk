// FILE: ./app/src/main/java/com/asterisk/voip/ui/dialplan/VoicePromptDialogFragment.kt
package com.asterisk.voip.ui.dialplan

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.viewModels
import com.asterisk.voip.data.model.VoicePrompt
import com.asterisk.voip.data.model.VoicePromptType
import com.asterisk.voip.databinding.DialogVoicePromptBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import java.io.File
import java.io.FileOutputStream

class VoicePromptDialogFragment : BottomSheetDialogFragment() {

    private var _binding: DialogVoicePromptBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DialPlanViewModel by viewModels({ requireParentFragment() })
    private var editingPrompt: VoicePrompt? = null
    private var selectedFilePath: String = ""
    private var isRecording = false

    companion object {
        private const val ARG_PROMPT = "prompt"

        fun newInstance(prompt: VoicePrompt?): VoicePromptDialogFragment {
            return VoicePromptDialogFragment().apply {
                arguments = Bundle().apply {
                    prompt?.let { putParcelable(ARG_PROMPT, it) }
                }
            }
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                handleSelectedFile(uri)
            }
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            pickAudioFile()
        } else {
            Snackbar.make(binding.root, "Permission denied", Snackbar.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogVoicePromptBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("DEPRECATION")
        editingPrompt = arguments?.getParcelable(ARG_PROMPT)
        setupSpinners()
        setupUI()
        setupListeners()
    }

    private fun setupSpinners() {
        // Prompt Type Spinner
        val types = VoicePromptType.values().map { formatType(it) }
        val typeAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            types
        )
        typeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerPromptType.adapter = typeAdapter

        binding.spinnerPromptType.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateFieldsForType(VoicePromptType.values()[position])
            }

            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // Language Spinner
        val languages = listOf("en-US", "en-GB", "es-ES", "fr-FR", "de-DE", "it-IT", "pt-BR", "zh-CN", "ja-JP")
        val languageAdapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            languages
        )
        languageAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        binding.spinnerLanguage.adapter = languageAdapter
    }

    private fun setupUI() {
        editingPrompt?.let { prompt ->
            binding.apply {
                tvTitle.text = "Edit Voice Prompt"
                etPromptName.setText(prompt.name)
                etPromptDescription.setText(prompt.description)
                spinnerPromptType.setSelection(prompt.fileType.ordinal)
                etTtsText.setText(prompt.ttsText)
                selectedFilePath = prompt.filePath
                tvSelectedFile.text = File(prompt.filePath).name

                val langIndex = (binding.spinnerLanguage.adapter as ArrayAdapter<String>)
                    .getPosition(prompt.language)
                if (langIndex >= 0) {
                    spinnerLanguage.setSelection(langIndex)
                }
            }
        } ?: run {
            binding.tvTitle.text = "Add Voice Prompt"
        }
    }

    private fun setupListeners() {
        binding.btnCancel.setOnClickListener { dismiss() }
        binding.btnSave.setOnClickListener {
            if (validateInput()) {
                savePrompt()
                dismiss()
            }
        }

        binding.btnSelectFile.setOnClickListener {
            checkPermissionAndPickFile()
        }

        binding.btnRecord.setOnClickListener {
            toggleRecording()
        }
    }

    private fun validateInput(): Boolean {
        var isValid = true

        binding.apply {
            if (etPromptName.text.isNullOrBlank()) {
                tilPromptName.error = "Required"
                isValid = false
            } else {
                tilPromptName.error = null
            }

            val type = VoicePromptType.values()[spinnerPromptType.selectedItemPosition]

            if (type == VoicePromptType.AUDIO_FILE && selectedFilePath.isEmpty()) {
                Snackbar.make(root, "Please select an audio file", Snackbar.LENGTH_SHORT).show()
                isValid = false
            }

            if (type == VoicePromptType.TTS && etTtsText.text.isNullOrBlank()) {
                tilTtsText.error = "Required for TTS"
                isValid = false
            } else {
                tilTtsText.error = null
            }
        }

        return isValid
    }

    private fun savePrompt() {
        val type = VoicePromptType.values()[binding.spinnerPromptType.selectedItemPosition]
        val language = binding.spinnerLanguage.selectedItem.toString()

        val prompt = VoicePrompt(
            id = editingPrompt?.id ?: 0,
            name = binding.etPromptName.text.toString().trim(),
            description = binding.etPromptDescription.text.toString().trim(),
            filePath = selectedFilePath,
            fileType = type,
            duration = 0, // TODO: Calculate duration
            language = language,
            ttsText = binding.etTtsText.text.toString().trim(),
            enabled = true
        )

        if (editingPrompt != null) {
            viewModel.updatePrompt(prompt)
        } else {
            viewModel.addPrompt(prompt)
        }
    }

    private fun updateFieldsForType(type: VoicePromptType) {
        binding.apply {
            when (type) {
                VoicePromptType.AUDIO_FILE -> {
                    layoutAudioFile.visibility = View.VISIBLE
                    layoutTts.visibility = View.GONE
                    layoutRecord.visibility = View.VISIBLE
                }
                VoicePromptType.TTS -> {
                    layoutAudioFile.visibility = View.GONE
                    layoutTts.visibility = View.VISIBLE
                    layoutRecord.visibility = View.GONE
                }
                VoicePromptType.SYSTEM_TONE -> {
                    layoutAudioFile.visibility = View.GONE
                    layoutTts.visibility = View.GONE
                    layoutRecord.visibility = View.GONE
                }
            }
        }
    }

    private fun checkPermissionAndPickFile() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            pickAudioFile()
        } else {
            permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun pickAudioFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        filePickerLauncher.launch(intent)
    }

    private fun handleSelectedFile(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
            val fileName = "prompt_${System.currentTimeMillis()}.mp3"
            val outputDir = File(requireContext().filesDir, "voice_prompts")
            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)

            inputStream?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }

            selectedFilePath = outputFile.absolutePath
            binding.tvSelectedFile.text = fileName
            Snackbar.make(binding.root, "File selected: $fileName", Snackbar.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Snackbar.make(binding.root, "Error: ${e.message}", Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun toggleRecording() {
        if (isRecording) {
            stopRecording()
        } else {
            startRecording()
        }
    }

    private fun startRecording() {
        if (ContextCompat.checkSelfPermission(
                requireContext(),
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val fileName = "recorded_${System.currentTimeMillis()}.mp3"
        val outputDir = File(requireContext().filesDir, "voice_prompts")
        outputDir.mkdirs()
        val outputFile = File(outputDir, fileName)

        // TODO: Implement actual recording using VoicePromptManager
        selectedFilePath = outputFile.absolutePath
        isRecording = true
        binding.btnRecord.text = "⏹ Stop Recording"
        Snackbar.make(binding.root, "Recording started...", Snackbar.LENGTH_SHORT).show()
    }

    private fun stopRecording() {
        // TODO: Stop recording using VoicePromptManager
        isRecording = false
        binding.btnRecord.text = "🎙 Record"
        binding.tvSelectedFile.text = File(selectedFilePath).name
        Snackbar.make(binding.root, "Recording saved", Snackbar.LENGTH_SHORT).show()
    }

    private fun formatType(type: VoicePromptType): String {
        return when (type) {
            VoicePromptType.AUDIO_FILE -> "Audio File"
            VoicePromptType.TTS -> "Text-to-Speech"
            VoicePromptType.SYSTEM_TONE -> "System Tone"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}