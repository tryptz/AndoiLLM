package com.tryptz.neuron.terminal

/**
 * Sticky on-screen modifiers (CTRL, ALT, SHIFT, FN), replacing Termux's
 * "hold volume-down for Ctrl" which nobody discovers.
 *
 * Tap once: armed for the next key. Tap again: locked until tapped a third
 * time. Reading an armed modifier consumes it, which is how the terminal view
 * asks ("is ctrl down for this key?").
 */
enum class ModifierState { OFF, ONCE, LOCKED }

class ModifierKeys(private val onChange: () -> Unit = {}) {
    enum class Key { CTRL, ALT, SHIFT, FN }

    private val states = Key.entries.associateWith { ModifierState.OFF }.toMutableMap()

    fun state(key: Key): ModifierState = states.getValue(key)

    fun tap(key: Key) {
        states[key] = when (states.getValue(key)) {
            ModifierState.OFF -> ModifierState.ONCE
            ModifierState.ONCE -> ModifierState.LOCKED
            ModifierState.LOCKED -> ModifierState.OFF
        }
        onChange()
    }

    /** True if [key] applies to the key being typed; consumes a one-shot press. */
    fun consume(key: Key): Boolean = when (states.getValue(key)) {
        ModifierState.OFF -> false
        ModifierState.LOCKED -> true
        ModifierState.ONCE -> {
            states[key] = ModifierState.OFF
            onChange()
            true
        }
    }

    fun reset() {
        Key.entries.forEach { states[it] = ModifierState.OFF }
        onChange()
    }
}
