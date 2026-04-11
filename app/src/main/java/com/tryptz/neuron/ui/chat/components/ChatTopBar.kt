package com.tryptz.neuron.ui.chat.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.DeviceTelemetry
import com.tryptz.neuron.domain.model.ModelDescriptor
import com.tryptz.neuron.ui.animation.MotionTokens
import com.tryptz.neuron.ui.animation.ThermalDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    activeModel: ModelDescriptor?,
    isGenerating: Boolean,
    telemetry: DeviceTelemetry,
    tokSec: Float,
    onNewChat: () -> Unit,
    onToggleModelSelector: () -> Unit,
    onToggleSettings: () -> Unit
) {
    val haptic = LocalHapticFeedback.current

    TopAppBar(
        title = {
            TextButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                onToggleModelSelector()
            }) {
                Text(
                    text = activeModel?.name ?: "Select Model",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(20.dp))
            }
        },
        navigationIcon = {
            IconButton(onClick = onNewChat) {
                Icon(Icons.Default.Add, "New chat")
            }
        },
        actions = {
            if (isGenerating) {
                ThermalDot(
                    thermalState = telemetry.thermalState,
                    modifier = Modifier.padding(end = 8.dp)
                )
                val animatedTokSec by animateFloatAsState(
                    targetValue = tokSec,
                    animationSpec = MotionTokens.Spring.GENTLE,
                    label = "tok_sec"
                )
                Text(
                    text = "%.0f t/s".format(animatedTokSec),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
            }
            IconButton(onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                onToggleSettings()
            }) {
                Icon(Icons.Default.Settings, "Settings")
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
    )
}
