package com.tryptz.neuron.ui.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tryptz.neuron.data.local.datastore.SettingsDataStore
import com.tryptz.neuron.data.repository.ConversationRepository
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.domain.usecase.*
import com.tryptz.neuron.inference.backend.InferenceEngine
import com.tryptz.neuron.inference.backend.ThermalManager
import timber.log.Timber
import com.tryptz.neuron.util.DeviceMonitor
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isGenerating: Boolean = false,
    val streamingContent: String = "",
    val streamingThinking: String = "",
    val isThinking: Boolean = false,
    val tokSec: Float = 0f,
    val tokenCount: Int = 0,
    val conversation: Conversation? = null,
    val activeModel: ModelDescriptor? = null,
    val telemetry: DeviceTelemetry = DeviceTelemetry(),
    val showModelSelector: Boolean = false,
    val showSettings: Boolean = false,
    val installedModels: List<ModelDescriptor> = emptyList(),
    val availableRamMb: Int = 0,
    val error: String? = null,
    // Model-load lifecycle: surfaces the 10-30s gap between selectModel() and
    // the active-model flow updating. isLoadingModel drives the overlay;
    // loadingStatus is the staged label inside it; loadConfirmation triggers
    // a transient success Snackbar that auto-clears.
    val isLoadingModel: Boolean = false,
    val loadingStatus: String? = null,
    val loadConfirmation: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val conversationRepo: ConversationRepository,
    private val modelRepo: ModelRepository,
    private val inferenceEngine: InferenceEngine,
    private val settingsStore: SettingsDataStore,
    private val deviceMonitor: DeviceMonitor,
    private val sendMessage: SendMessageUseCase,
    private val generateResponse: GenerateResponseUseCase,
    private val loadModel: LoadModelUseCase,
    private val executeCode: ExecuteCodeUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    val settings: StateFlow<InferenceSettings> = settingsStore.inferenceSettings
        .stateIn(viewModelScope, SharingStarted.Eagerly, InferenceSettings())

    val settingsLevel: StateFlow<SettingsLevel> = settingsStore.settingsLevel
        .stateIn(viewModelScope, SharingStarted.Eagerly, SettingsLevel.BASIC)

    private var generationJob: Job? = null
    private var messagesJob: Job? = null
    private var conversationId: String? = null

    init {
        viewModelScope.launch {
            modelRepo.observeInstalled().combine(modelRepo.observeLocalModels()) { registry, local ->
                registry + local.map { modelRepo.buildLocalDescriptor(it) }
            }.collect { models ->
                _uiState.update { it.copy(installedModels = models) }
            }
        }
        viewModelScope.launch {
            settingsStore.activeModelId.collect { id ->
                _uiState.update { state ->
                    val descriptor = id?.let { modelId ->
                        modelRepo.getDescriptorById(modelId)
                            ?: modelRepo.getLocalModel(modelId)?.let { modelRepo.buildLocalDescriptor(it) }
                    }
                    state.copy(activeModel = descriptor)
                }
            }
        }
        viewModelScope.launch {
            // Merge device-monitor telemetry (battery/RAM) with inference-engine
            // telemetry (tokSec/cpuTemp/thermalState) into one coherent snapshot
            // so the two sources can't clobber each other's fields.
            deviceMonitor.observeTelemetry().combine(inferenceEngine.telemetry) { device, inference ->
                // Prefer the inference engine's CPU-temp reading when it has one
                // (fresher during generation); fall back to the device monitor's
                // when idle. Derive thermalState from whichever temp we display so
                // the reported state can never disagree with the reported temp.
                val cpuTemp = inference.cpuTempCelsius ?: device.cpuTempCelsius
                device.copy(
                    thermalState = ThermalManager.classify(cpuTemp),
                    currentTokSec = inference.currentTokSec,
                    cpuTempCelsius = cpuTemp
                )
            }.collect { merged ->
                _uiState.update {
                    it.copy(
                        telemetry = merged,
                        availableRamMb = merged.ramTotalMb - merged.ramUsedMb
                    )
                }
            }
        }
    }

    fun loadConversation(id: String) {
        Timber.i("[op=load_conversation] convId=$id")
        conversationId = id
        // Cancel any prior collector so we don't leak coroutines and race on state.
        messagesJob?.cancel()
        messagesJob = viewModelScope.launch {
            conversationRepo.observeMessages(id).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun sendMessage(text: String, imageUris: List<String> = emptyList()) {
        viewModelScope.launch {
            val (convId, messages) = sendMessage(text, conversationId, _uiState.value.activeModel?.id, imageUris)
            if (conversationId != convId) {
                // New conversation: start observing its messages.
                conversationId = convId
                messagesJob?.cancel()
                messagesJob = viewModelScope.launch {
                    conversationRepo.observeMessages(convId).collect { msgs ->
                        _uiState.update { it.copy(messages = msgs) }
                    }
                }
            }
            // Drive generation off the list returned by the use case, not the
            // (possibly stale) state — the Room Flow may not have re-emitted yet.
            startGeneration(convId, messages)
        }
    }

    private fun startGeneration(convId: String, history: List<ChatMessage>) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, streamingContent = "", streamingThinking = "", error = null) }

            generateResponse(history, settings.value, convId).collect { result ->
                when (result) {
                    is GenerationResult.Streaming -> _uiState.update {
                        it.copy(
                            streamingContent = result.content,
                            streamingThinking = result.thinking,
                            isThinking = result.isThinking,
                            tokSec = result.tokSec,
                            tokenCount = result.tokenCount
                        )
                    }
                    is GenerationResult.Completed -> _uiState.update {
                        it.copy(isGenerating = false, streamingContent = "", streamingThinking = "")
                    }
                    is GenerationResult.Error -> _uiState.update {
                        it.copy(isGenerating = false, error = result.message)
                    }
                }
            }
        }
    }

    fun cancelGeneration() {
        Timber.i("[op=cancel_generation] tokens_so_far=${_uiState.value.tokenCount}")
        generationJob?.cancel()
        inferenceEngine.cancelGeneration()
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun regenerateLastResponse() {
        val convId = conversationId ?: return
        viewModelScope.launch {
            val last = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@launch
            conversationRepo.deleteMessagesAfter(convId, last.timestampMs - 1)
            // Re-query after the delete so generation runs on the trimmed history,
            // not the stale in-memory list (the Room Flow may not have re-emitted).
            val history = conversationRepo.getMessages(convId)
            startGeneration(convId, history)
        }
    }

    fun executeCode(codeBlock: CodeBlock) {
        viewModelScope.launch {
            val output = executeCode(codeBlock, settings.value)
            _uiState.update { state ->
                state.copy(messages = state.messages.map { msg ->
                    msg.copy(codeBlocks = msg.codeBlocks.map { b ->
                        if (b.id == codeBlock.id) b.copy(output = output) else b
                    })
                })
            }
        }
    }

    fun selectModel(modelId: String) {
        Timber.i("[op=select_model] modelId=$modelId")
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingModel = true, loadingStatus = "Freeing memory…", error = null)
            }

            // Explicit unload + GC to push the previous model's ~5 GB mmap out
            // before allocating the next one. Without this, two large mmap
            // regions are briefly resident and the lowmemorykiller can reap us.
            val tFreeStart = System.currentTimeMillis()
            inferenceEngine.unloadModel()
            System.gc()
            Runtime.getRuntime().gc()
            kotlinx.coroutines.delay(150)  // let concurrent GC settle
            Timber.i("[op=ram_freed] ms=${System.currentTimeMillis() - tFreeStart}")

            val name = _uiState.value.installedModels.firstOrNull { it.id == modelId }?.name ?: "model"
            _uiState.update { it.copy(loadingStatus = "Loading $name…") }

            val tLoadStart = System.currentTimeMillis()
            loadModel(modelId, settings.value)
                .onSuccess {
                    val ms = System.currentTimeMillis() - tLoadStart
                    Timber.i("[op=load_model_ok] modelId=$modelId name=\"$name\" ms=$ms")
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            loadingStatus = null,
                            loadConfirmation = "✓ $name loaded — ready to chat"
                        )
                    }
                }
                .onFailure { e ->
                    val ms = System.currentTimeMillis() - tLoadStart
                    Timber.e(e, "[op=load_model_fail] modelId=$modelId name=\"$name\" ms=$ms err=${e.message}")
                    _uiState.update {
                        it.copy(
                            isLoadingModel = false,
                            loadingStatus = null,
                            error = e.message ?: "Failed to load $name"
                        )
                    }
                }
        }
    }

    fun clearLoadConfirmation() { _uiState.update { it.copy(loadConfirmation = null) } }

    fun toggleModelSelector() {
        _uiState.update { it.copy(showModelSelector = !it.showModelSelector) }
        Timber.d("[op=toggle_model_selector] visible=${_uiState.value.showModelSelector}")
    }
    fun toggleSettings() {
        _uiState.update { it.copy(showSettings = !it.showSettings) }
        Timber.d("[op=toggle_settings] visible=${_uiState.value.showSettings}")
    }
    fun clearError() { _uiState.update { it.copy(error = null) } }

    fun updateSettings(update: (InferenceSettings) -> InferenceSettings) {
        viewModelScope.launch { settingsStore.updateSettings(update) }
    }

    fun setSettingsLevel(level: SettingsLevel) {
        viewModelScope.launch { settingsStore.setSettingsLevel(level) }
    }

    fun newConversation() {
        conversationId = null
        messagesJob?.cancel()
        generationJob?.cancel()
        _uiState.update { it.copy(messages = emptyList(), conversation = null, streamingContent = "", isGenerating = false) }
    }

    override fun onCleared() {
        generationJob?.cancel()
        messagesJob?.cancel()
        super.onCleared()
    }
}
