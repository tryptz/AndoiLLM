package com.tryptz.neuron.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.InferenceSettings
import com.tryptz.neuron.domain.model.ModelDescriptor

@Composable
fun BasicSettings(
    settings: InferenceSettings,
    activeModel: ModelDescriptor?,
    onUpdate: ((InferenceSettings) -> InferenceSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionHeader("Basic")

        SettingSlider(
            label = "Temperature",
            value = settings.temperature,
            range = 0f..2f,
            default = 0.7f,
            format = { "%.2f".format(it) },
            onValueChange = { v -> onUpdate { it.copy(temperature = v) } }
        )

        val maxCtx = activeModel?.maxContext ?: 131072
        SettingSlider(
            label = "Context Length",
            value = settings.contextLength.toFloat(),
            range = 512f..maxCtx.toFloat(),
            default = 4096f,
            format = { "${it.toInt()}" },
            onValueChange = { v -> onUpdate { it.copy(contextLength = v.toInt()) } }
        )

        var prompt by remember(settings.systemPrompt) { mutableStateOf(settings.systemPrompt) }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it; onUpdate { s -> s.copy(systemPrompt = it) } },
            label = { Text("System Prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 5
        )
    }
}
