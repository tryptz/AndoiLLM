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
        Text("Inference Backend (reload required)", style = MaterialTheme.typography.labelLarge)
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

        SettingSlider("Thread Count, 0=auto (reload required)", settings.threadCount.toFloat(), 0f..8f, 0f, format = { "${it.toInt()}" }) { v ->
            onUpdate { it.copy(threadCount = v.toInt()) }
        }
        SettingSlider("Batch Size (reload required)", settings.batchSize.toFloat(), 64f..2048f, 512f, format = { "${it.toInt()}" }) { v ->
            onUpdate { it.copy(batchSize = v.toInt()) }
        }

        // KV cache quantization — biggest research-informed tuning knob.
        // Q8_0 halves cache memory at near-zero quality loss; recommended
        // default for any context >8K. Q4_0 quarters it but costs ~+0.05
        // perplexity on long-context reasoning. FP16 is the baseline.
        Text("KV Cache Quantization (reload required)", style = MaterialTheme.typography.labelLarge)
        val kvOptions = listOf(Quantization.FP16, Quantization.Q8_0, Quantization.Q4_0)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            kvOptions.forEachIndexed { index, q ->
                SegmentedButton(
                    selected = settings.kvCacheQuant == q,
                    onClick = { onUpdate { it.copy(kvCacheQuant = q) } },
                    shape = SegmentedButtonDefaults.itemShape(index, kvOptions.size)
                ) {
                    Text(
                        when (q) {
                            Quantization.FP16 -> "FP16 (max quality)"
                            Quantization.Q8_0 -> "Q8_0 (recommended)"
                            Quantization.Q4_0 -> "Q4_0 (long context)"
                            else -> q.name
                        },
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }

        // Max thinking tokens — only meaningful for reasoning-capable models.
        if (activeModel?.capabilities?.reasoning == true) {
            SettingSlider(
                "Max Thinking Tokens",
                settings.maxThinkingTokens.toFloat(),
                range = 128f..4096f,
                default = 1024f,
                format = { "${it.toInt()}" }
            ) { v -> onUpdate { it.copy(maxThinkingTokens = v.toInt()) } }
        }

        // Throughput cap — useful with battery saver to bound power draw.
        SettingSlider(
            "Max Tok/s Cap (0=unlimited)",
            settings.maxTokSecCap.toFloat(),
            range = 0f..120f,
            default = 0f,
            format = { if (it == 0f) "off" else "${it.toInt()} tok/s" }
        ) { v -> onUpdate { it.copy(maxTokSecCap = v.toInt()) } }

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
        // Removed: "Background Inference" + "Wake Lock" — both were wired to the
        // InferenceService that the multi-agent run deleted as dead code. Re-adding
        // them needs reimplementing that foreground service first.

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
