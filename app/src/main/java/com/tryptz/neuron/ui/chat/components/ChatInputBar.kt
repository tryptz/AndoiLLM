package com.tryptz.neuron.ui.chat.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.ModelCapabilities
import com.tryptz.neuron.ui.animation.MotionTokens

@Composable
fun ChatInputBar(
    capabilities: ModelCapabilities?,
    isGenerating: Boolean,
    onSend: (String) -> Unit,
    onCancel: () -> Unit,
    onCameraTap: () -> Unit,
    onMicTap: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var inputText by remember { mutableStateOf(TextFieldValue()) }

    Surface(tonalElevation = 3.dp, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            if (capabilities?.vision == true) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LightClick); onCameraTap() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.CameraAlt, "Camera", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (capabilities?.audio == true) {
                IconButton(onClick = { haptic.performHapticFeedback(HapticFeedbackType.LightClick); onMicTap() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.Mic, "Microphone", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message…") },
                maxLines = 5,
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant
                )
            )

            Spacer(Modifier.width(8.dp))

            val sendScale by animateFloatAsState(
                targetValue = if (inputText.text.isNotBlank() || isGenerating) 1f else 0.8f,
                animationSpec = MotionTokens.Spring.RESPONSIVE,
                label = "send_scale"
            )

            FilledIconButton(
                onClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                    if (isGenerating) {
                        onCancel()
                    } else if (inputText.text.isNotBlank()) {
                        onSend(inputText.text)
                        inputText = TextFieldValue()
                    }
                },
                modifier = Modifier.size(44.dp).graphicsLayer { scaleX = sendScale; scaleY = sendScale },
                enabled = inputText.text.isNotBlank() || isGenerating
            ) {
                Crossfade(targetState = isGenerating, animationSpec = tween(200), label = "send_icon") { gen ->
                    if (gen) Icon(Icons.Default.Stop, "Stop") else Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
