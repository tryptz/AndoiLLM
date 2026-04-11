package com.tryptz.neuron.ui.modelmanager.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tryptz.neuron.data.local.entity.LocalModelEntity
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.download.ModelDownloadWorker
import com.tryptz.neuron.util.DeviceMonitor
import com.tryptz.neuron.util.GgufMetadata
import com.tryptz.neuron.util.GgufMetadataReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ModelManagerUiState(
    val allModels: List<ModelDescriptor> = emptyList(),
    val installedModelIds: Set<String> = emptySet(),
    val recommendedModels: List<ModelDescriptor> = emptyList(),
    val localModels: List<LocalModelEntity> = emptyList(),
    val downloads: Map<String, DownloadProgress> = emptyMap(),
    val availableRamMb: Int = 0,
    val totalRamMb: Int = 0,
    val deleteConfirmModelId: String? = null,
    val deleteConfirmLocalId: String? = null,
    val showImportDialog: Boolean = false,
    val importUri: Uri? = null,
    val importFileName: String = "",
    val importName: String = "",
    val importChatTemplate: ChatTemplate = ChatTemplate.CHATML,
    val importContextLength: Int = 4096,
    val isImporting: Boolean = false,
    val isDetecting: Boolean = false,
    val detectedMetadata: GgufMetadata? = null,
    val importArchitecture: String? = null,
    val importQuantization: String? = null,
    val importParamCount: Long? = null,
    val importError: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val modelRepo: ModelRepository,
    private val workManager: WorkManager,
    private val deviceMonitor: DeviceMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(ModelManagerUiState())
    val uiState: StateFlow<ModelManagerUiState> = _uiState.asStateFlow()

    init {
        _uiState.update {
            it.copy(
                allModels = modelRepo.getAllDescriptors(),
                recommendedModels = modelRepo.getRecommended(),
                availableRamMb = deviceMonitor.getAvailableRamMb(),
                totalRamMb = deviceMonitor.getTotalRamMb()
            )
        }

        viewModelScope.launch {
            modelRepo.observeInstalled().collect { installed ->
                _uiState.update { it.copy(installedModelIds = installed.map { m -> m.id }.toSet()) }
            }
        }

        viewModelScope.launch {
            modelRepo.observeLocalModels().collect { locals ->
                _uiState.update { it.copy(localModels = locals) }
            }
        }
    }

    // ── Download (registry models) ──

    fun downloadModel(modelId: String) {
        val request = ModelDownloadWorker.buildRequest(modelId)
        workManager.enqueue(request)

        viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0)
                val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0)
                val speed = info.progress.getLong(ModelDownloadWorker.KEY_SPEED_BPS, 0)

                when (info.state) {
                    WorkInfo.State.RUNNING -> {
                        _uiState.update { state ->
                            state.copy(downloads = state.downloads + (modelId to DownloadProgress(
                                modelId = modelId,
                                bytesDownloaded = downloaded,
                                totalBytes = total,
                                speedBytesPerSec = speed
                            )))
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.update { it.copy(downloads = it.downloads - modelId) }
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(downloads = it.downloads - modelId) }
                    }
                    else -> {}
                }
            }
        }
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelAllWorkByTag("model_download_$modelId")
        _uiState.update { it.copy(downloads = it.downloads - modelId) }
    }

    fun confirmDelete(modelId: String) {
        _uiState.update { it.copy(deleteConfirmModelId = modelId) }
    }

    fun dismissDelete() {
        _uiState.update { it.copy(deleteConfirmModelId = null) }
    }

    fun deleteModel(modelId: String) {
        viewModelScope.launch {
            modelRepo.deleteModel(modelId)
            _uiState.update { it.copy(deleteConfirmModelId = null) }
        }
    }

    // ── Local model import ──

    fun onFileSelected(uri: Uri, fileName: String) {
        val displayName = fileName.removeSuffix(".gguf")
        _uiState.update {
            it.copy(
                showImportDialog = true,
                importUri = uri,
                importFileName = fileName,
                importName = displayName,
                importChatTemplate = ChatTemplate.CHATML,
                importContextLength = 4096,
                isDetecting = true,
                detectedMetadata = null,
                importArchitecture = null,
                importQuantization = null,
                importParamCount = null,
                importError = null
            )
        }

        viewModelScope.launch {
            val metadata = withContext(Dispatchers.IO) {
                GgufMetadataReader.read(appContext, uri)
            }

            _uiState.update {
                it.copy(
                    isDetecting = false,
                    detectedMetadata = metadata,
                    importName = metadata?.name ?: displayName,
                    importChatTemplate = metadata?.inferredChatTemplate ?: ChatTemplate.CHATML,
                    importContextLength = metadata?.contextLength ?: 4096,
                    importArchitecture = metadata?.architecture,
                    importQuantization = metadata?.displayQuantization,
                    importParamCount = metadata?.parameterCount
                )
            }
        }
    }

    fun updateImportName(name: String) {
        _uiState.update { it.copy(importName = name) }
    }

    fun updateImportChatTemplate(template: ChatTemplate) {
        _uiState.update { it.copy(importChatTemplate = template) }
    }

    fun updateImportContextLength(length: Int) {
        _uiState.update { it.copy(importContextLength = length) }
    }

    fun dismissImportDialog() {
        _uiState.update { it.copy(showImportDialog = false, importUri = null, importError = null) }
    }

    fun confirmImport() {
        val state = _uiState.value
        val uri = state.importUri ?: return

        _uiState.update { it.copy(isImporting = true, importError = null) }

        viewModelScope.launch {
            modelRepo.importLocalModel(
                uri = uri,
                name = state.importName,
                chatTemplate = state.importChatTemplate,
                contextLength = state.importContextLength,
                architecture = state.importArchitecture,
                quantization = state.importQuantization,
                parameterCount = state.importParamCount
            ).onSuccess {
                _uiState.update { it.copy(showImportDialog = false, importUri = null, isImporting = false) }
            }.onFailure { e ->
                _uiState.update { it.copy(isImporting = false, importError = e.message ?: "Import failed") }
            }
        }
    }

    fun confirmDeleteLocal(id: String) {
        _uiState.update { it.copy(deleteConfirmLocalId = id) }
    }

    fun dismissDeleteLocal() {
        _uiState.update { it.copy(deleteConfirmLocalId = null) }
    }

    fun deleteLocalModel(id: String) {
        viewModelScope.launch {
            modelRepo.deleteLocalModel(id)
            _uiState.update { it.copy(deleteConfirmLocalId = null) }
        }
    }
}
