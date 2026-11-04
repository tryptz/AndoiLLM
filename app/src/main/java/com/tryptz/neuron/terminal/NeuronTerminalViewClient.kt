package com.tryptz.neuron.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import androidx.core.content.ContextCompat
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import timber.log.Timber

/**
 * Glue between the terminal view and Neuron. The defaults here fix the Termux
 * behaviours people complain about most:
 * - Character-based input is enforced, so Gboard/Samsung keyboards can't
 *   swallow keystrokes into an autocorrect "composing" span.
 * - Back is never remapped to Escape (it navigates; Esc is on the key row).
 * - A tap always brings the keyboard back, and pinch zoom is persisted.
 */
class NeuronTerminalViewClient(
    private val context: Context,
    private val modifiers: ModifierKeys,
    private val viewProvider: () -> TerminalView?,
    private val onFontScaled: (Float) -> Unit,
    private val onLongPressMenu: () -> Boolean = { false }
) : TerminalViewClient {

    override fun onScale(scale: Float): Float {
        // Termux's own trick: only commit a font step when the pinch has moved
        // far enough, otherwise text jitters with every frame.
        if (scale < 0.9f || scale > 1.1f) {
            onFontScaled(scale)
            return 1f
        }
        return scale
    }

    override fun onSingleTapUp(e: MotionEvent) {
        val view = viewProvider() ?: return
        view.requestFocus()
        ContextCompat.getSystemService(context, InputMethodManager::class.java)
            ?.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
    }

    override fun shouldBackButtonBeMappedToEscape(): Boolean = false
    override fun shouldEnforceCharBasedInput(): Boolean = true
    override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
    override fun isTerminalViewSelected(): Boolean = true
    override fun copyModeChanged(copyMode: Boolean) {}

    override fun onKeyDown(keyCode: Int, e: KeyEvent, session: TerminalSession): Boolean = false
    override fun onKeyUp(keyCode: Int, e: KeyEvent): Boolean = false
    override fun onLongPress(event: MotionEvent): Boolean = onLongPressMenu()

    override fun readControlKey(): Boolean = modifiers.consume(ModifierKeys.Key.CTRL)
    override fun readAltKey(): Boolean = modifiers.consume(ModifierKeys.Key.ALT)
    override fun readShiftKey(): Boolean = modifiers.consume(ModifierKeys.Key.SHIFT)
    override fun readFnKey(): Boolean = modifiers.consume(ModifierKeys.Key.FN)

    override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession): Boolean = false
    override fun onEmulatorSet() {}

    override fun logError(tag: String?, message: String?) { Timber.tag(tag ?: TAG).e(message) }
    override fun logWarn(tag: String?, message: String?) { Timber.tag(tag ?: TAG).w(message) }
    override fun logInfo(tag: String?, message: String?) { Timber.tag(tag ?: TAG).i(message) }
    override fun logDebug(tag: String?, message: String?) { Timber.tag(tag ?: TAG).d(message) }
    override fun logVerbose(tag: String?, message: String?) { Timber.tag(tag ?: TAG).v(message) }
    override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) { Timber.tag(tag ?: TAG).e(e, message) }
    override fun logStackTrace(tag: String?, e: Exception?) { Timber.tag(tag ?: TAG).e(e) }

    private companion object { const val TAG = "Neuron.TerminalView" }
}
