package com.tryptz.neuron.ui.browser

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tryptz.neuron.download.ActiveDownload
import com.tryptz.neuron.download.HfFileRef
import com.tryptz.neuron.download.ModelDownloads
import com.tryptz.neuron.download.PreflightResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The download sheet's state for one tapped file. */
sealed interface DownloadPrompt {
    data class Checking(val ref: HfFileRef) : DownloadPrompt
    data class Ready(val ref: HfFileRef, val result: PreflightResult.Ok) : DownloadPrompt
    data class Gated(val ref: HfFileRef) : DownloadPrompt
    data class Problem(val ref: HfFileRef, val message: String) : DownloadPrompt
}

@HiltViewModel
class HfBrowserViewModel @Inject constructor(
    private val downloads: ModelDownloads
) : ViewModel() {

    val active: StateFlow<List<ActiveDownload>> =
        downloads.active.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _prompt = MutableStateFlow<DownloadPrompt?>(null)
    val prompt: StateFlow<DownloadPrompt?> = _prompt.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private var checkJob: Job? = null

    /** Called when the page asks to download a .gguf (link tap or WebView download). */
    fun request(ref: HfFileRef) {
        checkJob?.cancel()
        _prompt.value = DownloadPrompt.Checking(ref)
        checkJob = viewModelScope.launch {
            _prompt.value = when (val r = downloads.preflight(ref)) {
                is PreflightResult.Ok -> DownloadPrompt.Ready(ref, r)
                is PreflightResult.Gated -> DownloadPrompt.Gated(ref)
                is PreflightResult.NotFound -> DownloadPrompt.Problem(ref, "File not found at this revision.")
                is PreflightResult.Failed -> DownloadPrompt.Problem(ref, r.message)
            }
        }
    }

    fun confirm() {
        val p = _prompt.value as? DownloadPrompt.Ready ?: return
        _prompt.value = null
        viewModelScope.launch {
            downloads.enqueue(p.result.parts)
            _toast.value = if (p.result.parts.size > 1) {
                "Downloading ${p.result.parts.size} parts of ${p.ref.fileName}"
            } else {
                "Downloading ${p.ref.fileName}"
            }
        }
    }

    fun dismiss() { checkJob?.cancel(); _prompt.value = null }
    fun notGguf() { _toast.value = "Neuron runs GGUF files. Open a repo's \"Files\" tab and pick a .gguf." }
    fun clearToast() { _toast.value = null }
}
