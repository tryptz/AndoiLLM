package com.tryptz.neuron.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.util.formatMbAsGb

@Composable
fun ExpertSettings(
    settings: InferenceSettings,
    activeModel: ModelDescriptor?,
    telemetry: DeviceTelemetry,
    onUpdate: ((InferenceSettings) -> InferenceSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Backend selection
        Text("Inference Backend", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            InferenceBackend.entries.forEachIndexed { index, backend ->
                val supported = activeModel?.supportedBackends?.contains(backend) ?: true
                SegmentedButton(
                    selected = settings.backend == backend,
                    onClick = { onUpdate { it.copy(backend = backend) } },
                    shape = SegmentedButtonDefaults.itemShape(index, InferenceBackend.entries.size),
                    enabled = supported || backend == InferenceBackend.AUTO
                ) { Text(backend.label, style = MaterialTheme.typography.labelSmall) }
            }
        }

        SettingSlider("Thread Count (0=auto)", settings.threadCount.toFloat(), 0f..8f, 0f, format = { "${it.toInt()}" }) { v ->
            onUpdate { it.copy(threadCount = v.toInt()) }
        }
        SettingSlider("Batch Size", settings.batchSize.toFloat(), 64f..2048f, 512f, format = { "${it.toInt()}" }) { v ->
            onUpdate { it.copy(batchSize = v.toInt()) }
        }

        // Thermal policy
        Text("Thermal Policy", style = MaterialTheme.typography.labelLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            ThermalPolicy.entries.forEachIndexed { index, policy ->
                SegmentedButton(
                    selected = settings.thermalPolicy == policy,
                    onClick = { onUpdate { it.copy(thermalPolicy = policy) } },
                    shape = SegmentedButtonDefaults.itemShape(index, ThermalPolicy.entries.size)
                ) { Text(policy.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall) }
            }
        }

        SettingToggle("Battery Saver", settings.batterySaver) { onUpdate { it.copy(batterySaver = !it.batterySaver) } }
        SettingToggle("Background Inference", settings.backgroundInference) { onUpdate { it.copy(backgroundInference = !it.backgroundInference) } }
        SettingToggle("Wake Lock", settings.wakeLock) { onUpdate { it.copy(wakeLock = !it.wakeLock) } }

        // Code execution limits
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Code Execution", style = MaterialTheme.typography.titleMedium)
        SettingSlider("Timeout (sec)", settings.codeTimeoutSec.toFloat(), 1f..60f, 10f, format = { "${it.toInt()}s" }) { v ->
            onUpdate { it.copy(codeTimeoutSec = v.toInt()) }
        }
        SettingSlider("Memory Limit", settings.codeMemoryMb.toFloat(), 16f..256f, 64f, format = { "${it.toInt()} MB" }) { v ->
            onUpdate { it.copy(codeMemoryMb = v.toInt()) }
        }
        SettingToggle("Allow Network in Code", settings.codeNetworkAllowed) { onUpdate { it.copy(codeNetworkAllowed = !it.codeNetworkAllowed) } }

        // Device status
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text("Device Status", style = MaterialTheme.typography.titleMedium)
        LinearProgressIndicator(
            progress = { telemetry.ramUsedMb.toFloat() / telemetry.ramTotalMb.coerceAtLeast(1) },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${telemetry.ramUsedMb.formatMbAsGb()} / ${telemetry.ramTotalMb.formatMbAsGb()} RAM used",
            style = MaterialTheme.typography.bodySmall
        )
    }
}
