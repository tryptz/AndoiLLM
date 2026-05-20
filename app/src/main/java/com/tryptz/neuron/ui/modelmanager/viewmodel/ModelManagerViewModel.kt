package com.tryptz.neuron.ui.modelmanager.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tryptz.neuron.data.local.entity.LocalModelEntity
import com.tryptz.neuron.data.remote.HfFile
import com.tryptz.neuron.data.remote.HfRepoSummary
import com.tryptz.neuron.data.remote.HfSort
import com.tryptz.neuron.data.remote.HuggingFaceClient
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.download.ModelDownloadWorker
import com.tryptz.neuron.util.DeviceMonitor
import com.tryptz.neuron.util.GgufMetadata
import com.tryptz.neuron.util.GgufMetadataReader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
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
    val importError: String? = null,
    // ── HF browser ──
    val hfQuery: String = "",
    val hfSort: HfSort = HfSort.TRENDING,
    val hfSearching: Boolean = false,
    val hfResults: List<HfRepoSummary> = emptyList(),
    val hfExpandedRepo: String? = null,
    val hfRepoFiles: List<HfFile> = emptyList(),
    val hfLoadingFiles: Boolean = false,
    val hfError: String? = null
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val modelRepo: ModelRepository,
    private val workManager: WorkManager,
    private val deviceMonitor: DeviceMonitor,
    private val hfClient: HuggingFaceClient
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

    /** Tracks the WorkInfo-collector Job per model so a re-download (or cancel)
     *  cancels the prior collector instead of leaking one per call. */
    private val downloadCollectors = mutableMapOf<String, Job>()

    fun downloadModel(modelId: String) {
        val request = ModelDownloadWorker.buildRequest(modelId)
        workManager.enqueue(request)

        // Cancel any collector left over from a previous download of this model.
        downloadCollectors.remove(modelId)?.cancel()

        downloadCollectors[modelId] = viewModelScope.launch {
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
                        downloadCollectors.remove(modelId)?.cancel()
                    }
                    WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(downloads = it.downloads - modelId) }
                        downloadCollectors.remove(modelId)?.cancel()
                    }
                    else -> {}
                }
            }
        }
    }

    fun cancelDownload(modelId: String) {
        workManager.cancelAllWorkByTag("model_download_$modelId")
        downloadCollectors.remove(modelId)?.cancel()
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

    // ── HF browser ──

    private var searchJob: Job? = null

    init {
        // Kick off an initial Trending fetch so the Discover section isn't empty.
        viewModelScope.launch { runHfSearch() }
    }

    fun setHfQuery(q: String) {
        _uiState.update { it.copy(hfQuery = q) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(350) // debounce typing
            runHfSearch()
        }
    }

    fun setHfSort(sort: HfSort) {
        _uiState.update { it.copy(hfSort = sort) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runHfSearch() }
    }

    private suspend fun runHfSearch() {
        val state = _uiState.value
        _uiState.update { it.copy(hfSearching = true, hfError = null) }
        try {
            val results = hfClient.search(state.hfQuery, state.hfSort)
            _uiState.update { it.copy(hfSearching = false, hfResults = results) }
            Timber.i("[op=hf_search_ok] count=${results.size} sort=${state.hfSort.apiKey}")
        } catch (e: Exception) {
            Timber.e(e, "[op=hf_search_fail] q=${state.hfQuery}")
            _uiState.update { it.copy(hfSearching = false, hfError = e.message ?: "Search failed") }
        }
    }

    fun toggleHfRepo(repoId: String) {
        val currentlyExpanded = _uiState.value.hfExpandedRepo == repoId
        if (currentlyExpanded) {
            _uiState.update { it.copy(hfExpandedRepo = null, hfRepoFiles = emptyList()) }
            return
        }
        _uiState.update { it.copy(hfExpandedRepo = repoId, hfLoadingFiles = true, hfRepoFiles = emptyList()) }
        viewModelScope.launch {
            try {
                val files = hfClient.listGgufFiles(repoId)
                _uiState.update { it.copy(hfLoadingFiles = false, hfRepoFiles = files) }
                Timber.i("[op=hf_files_ok] repo=$repoId count=${files.size}")
            } catch (e: Exception) {
                Timber.e(e, "[op=hf_files_fail] repo=$repoId")
                _uiState.update { it.copy(hfLoadingFiles = false, hfError = e.message ?: "Listing failed") }
            }
        }
    }

    /** Kick off a download for a specific .gguf file inside an HF repo. Uses the
     *  same WorkManager pipeline as catalog downloads — the worker's URL override
     *  branch handles the registration-as-local-model after completion. */
    fun downloadHfFile(repoId: String, file: HfFile) {
        val syntheticId = "hf:${repoId.replace('/', '_')}:${file.path}"
        val url = "https://huggingface.co/$repoId/resolve/main/${file.path}"
        val filename = file.path.substringAfterLast('/')
        val displayName = "${repoId.substringAfterLast('/')} / $filename"
        Timber.i("[op=hf_download_enqueue] id=$syntheticId repo=$repoId file=${file.path} url=$url")

        val request = ModelDownloadWorker.buildRequest(
            modelId = syntheticId,
            url = url,
            filename = filename,
            displayName = displayName,
            sourceRepo = repoId
        )
        workManager.enqueue(request)
        downloadCollectors.remove(syntheticId)?.cancel()
        downloadCollectors[syntheticId] = viewModelScope.launch {
            workManager.getWorkInfoByIdFlow(request.id).collect { info ->
                if (info == null) return@collect
                val downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0)
                val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0)
                val speed = info.progress.getLong(ModelDownloadWorker.KEY_SPEED_BPS, 0)
                when (info.state) {
                    WorkInfo.State.RUNNING -> _uiState.update { s ->
                        s.copy(downloads = s.downloads + (syntheticId to DownloadProgress(
                            modelId = syntheticId,
                            bytesDownloaded = downloaded,
                            totalBytes = total,
                            speedBytesPerSec = speed
                        )))
                    }
                    WorkInfo.State.SUCCEEDED, WorkInfo.State.FAILED, WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(downloads = it.downloads - syntheticId) }
                        downloadCollectors.remove(syntheticId)?.cancel()
                    }
                    else -> {}
                }
            }
        }
    }
}
