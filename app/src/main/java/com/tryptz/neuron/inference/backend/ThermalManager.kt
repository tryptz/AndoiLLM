package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.ThermalState

/**
 * Thermal-state classification for the UI (indicator colour, telemetry merge).
 *
 * Per-token throttling (pause / slow-down / tok/s cap) runs inside the Rust
 * core's decode loop (`neuron-core::thermal`), which uses these same
 * thresholds. Keep the two in sync; both sides have boundary tests.
 */
object ThermalManager {

    /** CPU temperature thresholds (°C) above which each thermal state is reported. */
    const val WARM_THRESHOLD_C = 65f
    const val HOT_THRESHOLD_C = 75f
    const val CRITICAL_THRESHOLD_C = 85f

    fun classify(tempCelsius: Float?): ThermalState = when {
        tempCelsius == null -> ThermalState.NOMINAL
        tempCelsius > CRITICAL_THRESHOLD_C -> ThermalState.CRITICAL
        tempCelsius > HOT_THRESHOLD_C -> ThermalState.HOT
        tempCelsius > WARM_THRESHOLD_C -> ThermalState.WARM
        else -> ThermalState.NOMINAL
    }
}
