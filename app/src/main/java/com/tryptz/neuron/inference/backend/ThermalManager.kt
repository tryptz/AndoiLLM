package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.ThermalPolicy
import com.tryptz.neuron.domain.model.ThermalState
import kotlinx.coroutines.delay

/**
 * Manages thermal throttling policy during inference.
 * Reads CPU temperature and applies configured delays to prevent
 * sustained overheating on the Snapdragon 8 Elite Gen 5.
 *
 * [classify] is the single source of truth for thermal-state thresholds —
 * DeviceMonitor delegates here rather than duplicating the cutoffs.
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

    suspend fun applyThrottling(
        policy: ThermalPolicy,
        cpuTempCelsius: Float?
    ) {
        val temp = cpuTempCelsius ?: return
        when (policy) {
            ThermalPolicy.PAUSE -> if (temp > CRITICAL_THRESHOLD_C) delay(1000)
            ThermalPolicy.SLOW_DOWN -> if (temp > HOT_THRESHOLD_C) delay(50)
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
