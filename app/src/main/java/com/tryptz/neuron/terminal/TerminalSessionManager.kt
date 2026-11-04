package com.tryptz.neuron.terminal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

sealed interface InstallState {
    data object Checking : InstallState
    data class Installing(val progress: BootstrapProgress?) : InstallState
    data class Failed(val message: String) : InstallState
    data object Ready : InstallState
}

data class SessionInfo(val handle: String, val title: String, val running: Boolean)

/** Which shell sessions run. */
enum class ShellMode {
    /** Full Termux userland: bash, apt/pkg, termux-exec. */
    USERLAND,
    /** Android's /system/bin/sh: used when the userland can't run under this install. */
    SYSTEM
}

data class TerminalState(
    val install: InstallState = InstallState.Checking,
    val sessions: List<SessionInfo> = emptyList(),
    val current: Int = 0,
    /** Bumped on every screen change so the view knows to redraw. */
    val revision: Long = 0,
    val canonicalPath: Boolean = true,
    val mode: ShellMode = ShellMode.SYSTEM,
    /** Why the userland is unavailable, shown as a banner in SYSTEM mode. */
    val userlandUnavailableReason: String? = null
)

/**
 * Owns the bootstrap install and every terminal session. A @Singleton, so
 * sessions survive leaving the screen; [TerminalService] keeps the process
 * alive while any session exists (Termux's "my session vanished" complaint).
 */
@Singleton
class TerminalSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: TerminalPreferences
) : TerminalSessionClient {

    val paths = TermuxPaths(context.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val installLock = Mutex()
    private val sessions = mutableListOf<TerminalSession>()

    private val _state = MutableStateFlow(TerminalState(canonicalPath = paths.isCanonical))
    val state: StateFlow<TerminalState> = _state.asStateFlow()

    /** Line queued for the current session, typed once its shell is running. */
    private var pendingLine: String? = null

    /** Set by the screen; the terminal view to redraw on output. */
    var onScreenUpdate: (() -> Unit)? = null

    val currentSession: TerminalSession? get() = sessions.getOrNull(_state.value.current)

    fun ensureInstalled() {
        scope.launch {
            installLock.withLock {
                if (paths.isInstalled) {
                    ready(ShellMode.USERLAND, null)
                    return@withLock
                }
                val unavailable = userlandUnavailableReason()
                if (unavailable != null) {
                    Timber.i("[op=terminal_system_shell] reason=\"$unavailable\"")
                    ready(ShellMode.SYSTEM, unavailable)
                    return@withLock
                }
                _state.update { it.copy(install = InstallState.Installing(null)) }
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        context.assets.open(BOOTSTRAP_ASSET).use { zip ->
                            BootstrapInstaller(paths).install(zip) { p ->
                                _state.update { it.copy(install = InstallState.Installing(p)) }
                            }
                        }
                        writeFirstRunFiles()
                    }
                }
                result.onSuccess {
                    Timber.i("[op=termux_bootstrap_installed] prefix=${paths.prefix}")
                    ready(ShellMode.USERLAND, null)
                }.onFailure { e ->
                    Timber.e(e, "[op=termux_bootstrap_failed]")
                    _state.update { it.copy(install = InstallState.Failed(e.message ?: e.javaClass.simpleName)) }
                }
            }
        }
    }

    private fun ready(mode: ShellMode, reason: String?) {
        _state.update { it.copy(install = InstallState.Ready, mode = mode, userlandUnavailableReason = reason) }
        if (sessions.isEmpty()) newSession()
    }

    /**
     * The userland only runs when the package was built for this exact path and
     * the bootstrap was bundled (which Gradle does only for com.termux installs).
     */
    private fun userlandUnavailableReason(): String? = when {
        !paths.isCanonical ->
            "Termux packages need the app installed as com.termux (this install is ${context.packageName}). " +
                "Using Android's system shell."
        runCatching { context.assets.open(BOOTSTRAP_ASSET).close() }.isFailure ->
            "This build doesn't bundle the Termux bootstrap. Using Android's system shell."
        else -> null
    }

    fun newSession(): TerminalSession {
        val session = if (_state.value.mode == ShellMode.USERLAND && paths.isInstalled) {
            TerminalSession(
                paths.login.absolutePath,
                paths.home.absolutePath,
                arrayOf("-login"),
                TermuxEnvironment.toEnvArray(TermuxEnvironment.build(paths)),
                TRANSCRIPT_ROWS,
                this
            )
        } else {
            val home = java.io.File(context.filesDir, "home").apply { mkdirs() }
            val env = TermuxEnvironment.systemShell(home, context.cacheDir)
            TerminalSession(SYSTEM_SHELL, home.absolutePath, arrayOf("-sh"), TermuxEnvironment.toEnvArray(env), TRANSCRIPT_ROWS, this)
        }
        sessions += session
        publish(current = sessions.lastIndex)
        TerminalService.start(context)
        return session
    }

    fun switchTo(index: Int) {
        if (index in sessions.indices) publish(current = index)
    }

    fun close(index: Int) {
        sessions.getOrNull(index)?.finishIfRunning()
        removeSession(index)
    }

    fun closeAll() {
        sessions.forEach { it.finishIfRunning() }
        sessions.clear()
        publish(current = 0)
        TerminalService.stop(context)
    }

    /**
     * Types [input] at the prompt of the current session without pressing
     * Enter (see [TerminalInputStager]). Waits for the shell process if the
     * session hasn't started yet, since writes before that are dropped.
     */
    fun typeIntoCurrent(input: TerminalInput) {
        val bash = _state.value.mode == ShellMode.USERLAND
        val line = TerminalInputStager.commandFor(input, java.io.File(paths.home, ".neuron/snippets"), bash)
        pendingLine = line
        deliverPendingInput()
    }

    /** The last [maxLines] lines of the current session, trimmed. */
    fun transcriptTail(maxLines: Int = 80, maxChars: Int = 6000): String {
        val text = currentSession?.emulator?.screen?.transcriptTextWithoutJoinedLines ?: return ""
        return tail(text, maxLines, maxChars)
    }

    fun deliverPendingInput() {
        val line = pendingLine ?: return
        val s = currentSession ?: return
        if (_state.value.install != InstallState.Ready || s.pid <= 0) return
        pendingLine = null
        val bytes = line.toByteArray()
        s.write(bytes, 0, bytes.size)
    }

    private fun removeSession(index: Int) {
        if (index !in sessions.indices) return
        sessions.removeAt(index)
        publish(current = (_state.value.current).coerceAtMost((sessions.size - 1).coerceAtLeast(0)))
        if (sessions.isEmpty()) TerminalService.stop(context)
    }

    private fun publish(current: Int = _state.value.current) {
        _state.update {
            it.copy(
                sessions = sessions.mapIndexed { i, s ->
                    SessionInfo(handle = s.mHandle, title = s.title?.takeIf { t -> t.isNotBlank() } ?: "Session ${i + 1}", running = s.isRunning)
                },
                current = current,
                revision = it.revision + 1
            )
        }
    }

    /** One-time comfort files: welcome text and storage hint instead of a blank prompt. */
    private fun writeFirstRunFiles() {
        val motd = java.io.File(paths.home, ".termux/motd.sh")
        motd.parentFile?.mkdirs()
        motd.writeText(
            """
            |#!/data/data/com.termux/files/usr/bin/sh
            |printf '\033[1;36mNeuron terminal\033[0m — full Termux userland (apt).\n'
            |printf '  pkg update        refresh package lists (run this first)\n'
            |printf '  pkg install git python clang   install anything from Termux repos\n'
            |printf '  ~/storage         phone storage, after tapping Storage in the menu\n'
            |printf '  Ask Neuron        send this screen to the chat\n\n'
            |""".trimMargin()
        )
        motd.setExecutable(true, true)
    }

    // ───────────────────────── TerminalSessionClient ─────────────────────────

    override fun onTextChanged(changedSession: TerminalSession) {
        if (changedSession == currentSession) {
            // First output means the shell is up: type anything queued from chat.
            if (pendingLine != null) deliverPendingInput()
            onScreenUpdate?.invoke()
        }
    }

    override fun onTitleChanged(changedSession: TerminalSession) = publish()

    override fun onSessionFinished(finishedSession: TerminalSession) {
        // Close finished sessions instead of leaving a dead "[Process completed]" screen.
        val idx = sessions.indexOf(finishedSession)
        if (idx >= 0) scope.launch { removeSession(idx) }
    }

    override fun onCopyTextToClipboard(session: TerminalSession, text: String?) {
        if (text.isNullOrEmpty()) return
        val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText("terminal", text))
    }

    override fun onPasteTextFromClipboard(session: TerminalSession?) {
        val cm = ContextCompat.getSystemService(context, ClipboardManager::class.java) ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        session?.emulator?.paste(text)
    }

    override fun onBell(session: TerminalSession) {
        scope.launch {
            if (!prefs.vibrateOnBell.first()) return@launch
            val v = ContextCompat.getSystemService(context, Vibrator::class.java) ?: return@launch
            v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    override fun onColorsChanged(session: TerminalSession) { onScreenUpdate?.invoke() }
    override fun onTerminalCursorStateChange(state: Boolean) { onScreenUpdate?.invoke() }
    override fun getTerminalCursorStyle(): Int? = null

    override fun logError(tag: String?, message: String?) { Timber.tag(tag ?: TAG).e(message) }
    override fun logWarn(tag: String?, message: String?) { Timber.tag(tag ?: TAG).w(message) }
    override fun logInfo(tag: String?, message: String?) { Timber.tag(tag ?: TAG).i(message) }
    override fun logDebug(tag: String?, message: String?) { Timber.tag(tag ?: TAG).d(message) }
    override fun logVerbose(tag: String?, message: String?) { Timber.tag(tag ?: TAG).v(message) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Timber.tag(tag ?: TAG).e(e, message) }
    override fun logStackTrace(tag: String?, e: Exception?) { Timber.tag(tag ?: TAG).e(e) }

    companion object {
        const val BOOTSTRAP_ASSET = "termux/bootstrap-aarch64.zip"
        const val SYSTEM_SHELL = "/system/bin/sh"
        const val TRANSCRIPT_ROWS = 10_000
        private const val TAG = "Neuron.Terminal"

        fun tail(text: String, maxLines: Int, maxChars: Int): String {
            val lines = text.trimEnd().lines()
            return lines.takeLast(maxLines).joinToString("\n").takeLast(maxChars)
        }
    }
}
