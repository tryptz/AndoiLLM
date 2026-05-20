package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.ThermalState
import org.junit.Test
import kotlin.test.assertEquals

class ThermalManagerTest {

    @Test
    fun `null temperature is nominal`() {
        assertEquals(ThermalState.NOMINAL, ThermalManager.classify(null))
    }

    @Test
    fun `low temperature is nominal`() {
        assertEquals(ThermalState.NOMINAL, ThermalManager.classify(40f))
        assertEquals(ThermalState.NOMINAL, ThermalManager.classify(65f)) // boundary: not > 65
    }

    @Test
    fun `above warm threshold is warm`() {
        assertEquals(ThermalState.WARM, ThermalManager.classify(65.1f))
        assertEquals(ThermalState.WARM, ThermalManager.classify(75f)) // boundary: not > 75
    }

    @Test
    fun `above hot threshold is hot`() {
        assertEquals(ThermalState.HOT, ThermalManager.classify(75.1f))
        assertEquals(ThermalState.HOT, ThermalManager.classify(85f)) // boundary: not > 85
    }

    @Test
    fun `above critical threshold is critical`() {
        assertEquals(ThermalState.CRITICAL, ThermalManager.classify(85.1f))
        assertEquals(ThermalState.CRITICAL, ThermalManager.classify(120f))
    }

    @Test
    fun `threshold constants match expected values`() {
        assertEquals(65f, ThermalManager.WARM_THRESHOLD_C)
        assertEquals(75f, ThermalManager.HOT_THRESHOLD_C)
        assertEquals(85f, ThermalManager.CRITICAL_THRESHOLD_C)
    }
}
