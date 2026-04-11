package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class DeviceTelemetry(
    val thermalState: ThermalState = ThermalState.NOMINAL,
    val ramUsedMb: Int = 0,
    val ramTotalMb: Int = 0,
    val batteryPercent: Int = 100,
    val estimatedBatteryHours: Float? = null,
    val currentTokSec: Float = 0f,
    val cpuTempCelsius: Float? = null
)
