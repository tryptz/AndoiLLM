package com.tryptz.neuron.terminal

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModifierKeysTest {

    @Test
    fun `tap cycles off once locked off`() {
        val m = ModifierKeys()
        assertEquals(ModifierState.OFF, m.state(ModifierKeys.Key.CTRL))
        m.tap(ModifierKeys.Key.CTRL); assertEquals(ModifierState.ONCE, m.state(ModifierKeys.Key.CTRL))
        m.tap(ModifierKeys.Key.CTRL); assertEquals(ModifierState.LOCKED, m.state(ModifierKeys.Key.CTRL))
        m.tap(ModifierKeys.Key.CTRL); assertEquals(ModifierState.OFF, m.state(ModifierKeys.Key.CTRL))
    }

    @Test
    fun `one-shot modifier is consumed by the next key`() {
        val m = ModifierKeys()
        m.tap(ModifierKeys.Key.ALT)
        assertTrue(m.consume(ModifierKeys.Key.ALT))
        assertFalse(m.consume(ModifierKeys.Key.ALT))
    }

    @Test
    fun `locked modifier survives keys until tapped off`() {
        val m = ModifierKeys()
        repeat(2) { m.tap(ModifierKeys.Key.CTRL) }
        repeat(5) { assertTrue(m.consume(ModifierKeys.Key.CTRL)) }
        m.tap(ModifierKeys.Key.CTRL)
        assertFalse(m.consume(ModifierKeys.Key.CTRL))
    }

    @Test
    fun `modifiers are independent and change callbacks fire`() {
        var changes = 0
        val m = ModifierKeys { changes++ }
        m.tap(ModifierKeys.Key.CTRL)
        assertFalse(m.consume(ModifierKeys.Key.SHIFT))
        assertTrue(m.consume(ModifierKeys.Key.CTRL))
        m.reset()
        assertEquals(3, changes)
    }
}
