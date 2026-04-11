package com.tryptz.neuron.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.InferenceSettings
import com.tryptz.neuron.domain.model.ModelDescriptor
import com.tryptz.neuron.domain.model.ReasoningEffort

@Composable
fun AdvancedSettings(
    settings: InferenceSettings,
    activeModel: ModelDescriptor?,
    onUpdate: ((InferenceSettings) -> InferenceSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingSlider("Top P", settings.topP, 0f..1f, 0.9f, format = { "%.2f".format(it) }) { v ->
            onUpdate { it.copy(topP = v) }
        }
        SettingSlider("Top K", settings.topK.toFloat(), 1f..100f, 40f, format = { "${it.toInt()}" }) { v ->
            onUpdate { it.copy(topK = v.toInt()) }
        }
        SettingSlider("Min P", settings.minP, 0f..0.5f, 0.05f, format = { "%.3f".format(it) }) { v ->
            onUpdate { it.copy(minP = v) }
        }
        SettingSlider("Repeat Penalty", settings.repeatPenalty, 1f..2f, 1.1f, format = { "%.2f".format(it) }) { v ->
            onUpdate { it.copy(repeatPenalty = v) }
        }

        if (activeModel?.capabilities?.reasoning == true) {
            Spacer(Modifier.height(8.dp))
            Text("Reasoning Effort", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ReasoningEffort.entries.forEachIndexed { index, effort ->
                    SegmentedButton(
                        selected = settings.reasoningEffort == effort,
                        onClick = { onUpdate { it.copy(reasoningEffort = effort) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ReasoningEffort.entries.size)
                    ) {
                        Text(effort.name.lowercase().replaceFirstChar { it.uppercase() })
                    }
                }
            }
        }
    }
}
