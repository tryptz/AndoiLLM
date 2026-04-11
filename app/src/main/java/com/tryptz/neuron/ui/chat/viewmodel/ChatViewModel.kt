package com.tryptz.neuron.ui.chat.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tryptz.neuron.data.local.datastore.SettingsDataStore
import com.tryptz.neuron.data.repository.ConversationRepository
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.domain.usecase.*
import com.tryptz.neuron.inference.backend.InferenceEngine
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
    val error: String? = null
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
            deviceMonitor.observeTelemetry().collect { t ->
                _uiState.update { it.copy(telemetry = t, availableRamMb = t.ramTotalMb - t.ramUsedMb) }
            }
        }
        viewModelScope.launch {
            inferenceEngine.telemetry.collect { t -> _uiState.update { it.copy(telemetry = t) } }
        }
    }

    fun loadConversation(id: String) {
        conversationId = id
        viewModelScope.launch {
            conversationRepo.observeMessages(id).collect { msgs ->
                _uiState.update { it.copy(messages = msgs) }
            }
        }
    }

    fun sendMessage(text: String, imageUris: List<String> = emptyList()) {
        viewModelScope.launch {
            val (convId, _) = sendMessage(text, conversationId, _uiState.value.activeModel?.id, imageUris)
            conversationId = convId
            startGeneration(convId)
        }
    }

    private fun startGeneration(convId: String) {
        generationJob?.cancel()
        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true, streamingContent = "", streamingThinking = "", error = null) }

            generateResponse(_uiState.value.messages, settings.value, convId).collect { result ->
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
        generationJob?.cancel()
        inferenceEngine.cancelGeneration()
        _uiState.update { it.copy(isGenerating = false) }
    }

    fun regenerateLastResponse() {
        val convId = conversationId ?: return
        viewModelScope.launch {
            val last = _uiState.value.messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@launch
            conversationRepo.deleteMessagesAfter(convId, last.timestampMs - 1)
            startGeneration(convId)
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
        viewModelScope.launch {
            loadModel(modelId, settings.value)
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }

    fun toggleModelSelector() { _uiState.update { it.copy(showModelSelector = !it.showModelSelector) } }
    fun toggleSettings() { _uiState.update { it.copy(showSettings = !it.showSettings) } }
    fun clearError() { _uiState.update { it.copy(error = null) } }

    fun updateSettings(update: (InferenceSettings) -> InferenceSettings) {
        viewModelScope.launch { settingsStore.updateSettings(update) }
    }

    fun setSettingsLevel(level: SettingsLevel) {
        viewModelScope.launch { settingsStore.setSettingsLevel(level) }
    }

    fun newConversation() {
        conversationId = null
        _uiState.update { it.copy(messages = emptyList(), conversation = null, streamingContent = "", isGenerating = false) }
    }

    override fun onCleared() { generationJob?.cancel(); super.onCleared() }
}
