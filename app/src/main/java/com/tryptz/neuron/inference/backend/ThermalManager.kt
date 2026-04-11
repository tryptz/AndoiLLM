package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.ThermalPolicy
import com.tryptz.neuron.domain.model.ThermalState
import kotlinx.coroutines.delay

/**
 * Manages thermal throttling policy during inference.
 * Reads CPU temperature and applies configured delays to prevent
 * sustained overheating on the Snapdragon 8 Elite Gen 5.
 */
object ThermalManager {

    fun classify(tempCelsius: Float?): ThermalState = when {
        tempCelsius == null -> ThermalState.NOMINAL
        tempCelsius > 85f -> ThermalState.CRITICAL
        tempCelsius > 75f -> ThermalState.HOT
        tempCelsius > 65f -> ThermalState.WARM
        else -> ThermalState.NOMINAL
    }

    suspend fun applyThrottling(
        policy: ThermalPolicy,
        cpuTempCelsius: Float?
    ) {
        val temp = cpuTempCelsius ?: return
        when (policy) {
            ThermalPolicy.PAUSE -> if (temp > 85f) delay(1000)
            ThermalPolicy.SLOW_DOWN -> if (temp > 75f) delay(50)
            ThermalPolicy.IGNORE -> { /* no-op */ }
        }
    }

    suspend fun applyTokSecCap(
        currentTokSec: Float,
        maxTokSecCap: Int
    ) {
        if (maxTokSecCap > 0 && currentTokSec > maxTokSecCap) {
            delay((1000f / maxTokSecCap).toLong())
        }
    }
}
