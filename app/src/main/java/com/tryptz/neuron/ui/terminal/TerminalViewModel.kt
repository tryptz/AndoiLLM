package com.tryptz.neuron.ui.terminal

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tryptz.neuron.terminal.ModifierKeys
import com.tryptz.neuron.terminal.ModifierState
import com.tryptz.neuron.terminal.StorageSymlinks
import com.tryptz.neuron.terminal.TerminalPreferences
import com.tryptz.neuron.terminal.TerminalSessionManager
import com.tryptz.neuron.terminal.TerminalState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class TerminalViewModel @Inject constructor(
    val manager: TerminalSessionManager,
    private val prefs: TerminalPreferences
) : ViewModel() {

    val state: StateFlow<TerminalState> = manager.state
    val fontSp: StateFlow<Int> = prefs.fontSp.stateIn(viewModelScope, SharingStarted.Eagerly, TerminalPreferences.DEFAULT_FONT_SP)
    val keepAwake: StateFlow<Boolean> = prefs.keepAwake.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val vibrateOnBell: StateFlow<Boolean> = prefs.vibrateOnBell.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _modifierStates = MutableStateFlow(ModifierKeys.Key.entries.associateWith { ModifierState.OFF })
    val modifierStates: StateFlow<Map<ModifierKeys.Key, ModifierState>> = _modifierStates.asStateFlow()

    val modifiers = ModifierKeys { publishModifiers() }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { manager.ensureInstalled() }

    private fun publishModifiers() {
        _modifierStates.value = ModifierKeys.Key.entries.associateWith { modifiers.state(it) }
    }

    fun retryInstall() = manager.ensureInstalled()
    fun newSession() { manager.newSession() }
    fun switchTo(i: Int) = manager.switchTo(i)
    fun close(i: Int) = manager.close(i)

    fun stepFont(increase: Boolean) {
        viewModelScope.launch { prefs.setFontSp(fontSp.value + if (increase) 1 else -1) }
    }

    fun setKeepAwake(on: Boolean) { viewModelScope.launch { prefs.setKeepAwake(on) } }
    fun setVibrateOnBell(on: Boolean) { viewModelScope.launch { prefs.setVibrateOnBell(on) } }

    fun setUpStorage() {
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { StorageSymlinks.create(manager.paths) } }
            _message.value = r.fold({ "Storage linked at ~/storage" }, { "Storage setup failed: ${it.message}" })
        }
    }

    fun clearMessage() { _message.value = null }

    suspend fun awaitReady() { state.first { it.install is com.tryptz.neuron.terminal.InstallState.Ready } }
}
