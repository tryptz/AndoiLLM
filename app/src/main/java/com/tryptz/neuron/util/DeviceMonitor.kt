package com.tryptz.neuron.util

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.tryptz.neuron.domain.model.DeviceTelemetry
import com.tryptz.neuron.domain.model.ThermalState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    fun observeTelemetry(intervalMs: Long = 2000): Flow<DeviceTelemetry> = flow {
        while (true) {
            emit(snapshot())
            delay(intervalMs)
        }
    }

    fun snapshot(): DeviceTelemetry {
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPct = if (level >= 0 && scale > 0) (level * 100 / scale) else 100

        val cpuTemp = readCpuTemperature()

        return DeviceTelemetry(
            thermalState = classifyThermal(cpuTemp),
            ramUsedMb = ((memInfo.totalMem - memInfo.availMem) / (1024 * 1024)).toInt(),
            ramTotalMb = (memInfo.totalMem / (1024 * 1024)).toInt(),
            batteryPercent = batteryPct,
            cpuTempCelsius = cpuTemp
        )
    }

    fun getAvailableRamMb(): Int {
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    fun getTotalRamMb(): Int {
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }

    private fun readCpuTemperature(): Float? {
        return try {
            // Try common thermal zone paths on Qualcomm SoCs
            val paths = listOf(
                "/sys/class/thermal/thermal_zone0/temp",
                "/sys/devices/virtual/thermal/thermal_zone0/temp"
            )
            for (path in paths) {
                val file = File(path)
                if (file.exists()) {
                    val raw = file.readText().trim().toFloatOrNull() ?: continue
                    return if (raw > 1000) raw / 1000f else raw
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun classifyThermal(tempC: Float?): ThermalState = when {
        tempC == null -> ThermalState.NOMINAL
        tempC > 85f -> ThermalState.CRITICAL
        tempC > 75f -> ThermalState.HOT
        tempC > 65f -> ThermalState.WARM
        else -> ThermalState.NOMINAL
    }
}
