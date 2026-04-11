package com.tryptz.neuron.ui.settings.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    default: Float,
    steps: Int = 0,
    format: (Float) -> String,
    onValueChange: (Float) -> Unit
) {
    val isModified = value != default

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            if (isModified) {
                TextButton(
                    onClick = { onValueChange(default) },
                    modifier = Modifier.height(28.dp)
                ) {
                    Text("Reset", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                format(value),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
