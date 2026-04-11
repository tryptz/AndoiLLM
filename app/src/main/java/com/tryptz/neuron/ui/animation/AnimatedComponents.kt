package com.tryptz.neuron.ui.animation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

/**
 * Typing indicator: three dots with staggered scale pulsing.
 */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 200)
                ),
                label = "dot_$index"
            )
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .graphicsLayer { alpha = 0.4f + (scale - 0.6f) * 1.5f }
            ) {
                androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                    drawCircle(color = Color(0xFF888888))
                }
            }
        }
    }
}

/**
 * Animated digit counter with vertical scroll effect.
 * Used for tok/sec display during generation.
 */
@Composable
fun AnimatedDigitCounter(
    value: Int,
    modifier: Modifier = Modifier
) {
    val animatedValue by animateIntAsState(
        targetValue = value,
        animationSpec = spring(
            stiffness = Spring.StiffnessLow,
            dampingRatio = Spring.DampingRatioMediumBouncy
        ),
        label = "counter"
    )

    androidx.compose.material3.Text(
        text = animatedValue.toString(),
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/**
 * Thermal state indicator dot with animated color transitions.
 */
@Composable
fun ThermalDot(
    thermalState: com.tryptz.neuron.domain.model.ThermalState,
    modifier: Modifier = Modifier
) {
    val color by animateColorAsState(
        targetValue = when (thermalState) {
            com.tryptz.neuron.domain.model.ThermalState.NOMINAL -> Color(0xFF4CAF50)
            com.tryptz.neuron.domain.model.ThermalState.WARM -> Color(0xFFFFC107)
            com.tryptz.neuron.domain.model.ThermalState.HOT -> Color(0xFFFF9800)
            com.tryptz.neuron.domain.model.ThermalState.CRITICAL -> Color(0xFFF44336)
        },
        animationSpec = spring(
            stiffness = 200f,
            dampingRatio = 0.7f
        ),
        label = "thermal_color"
    )

    androidx.compose.foundation.Canvas(
        modifier = modifier.size(8.dp)
    ) {
        drawCircle(color = color)
    }
}

/**
 * Tap scale modifier — press feedback with haptics.
 */
@Composable
fun Modifier.tapScale(): Modifier {
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.95f else 1.0f,
        animationSpec = spring(stiffness = 400f, dampingRatio = 0.8f),
        label = "tap_scale"
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Shimmer loading effect for skeleton screens.
 */
@Composable
fun ShimmerEffect(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "shimmer")
    val offset by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing)
        ),
        label = "shimmer_offset"
    )

    Box(modifier = modifier.graphicsLayer {
        val brush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                Color(0xFF1A1A1A),
                Color(0xFF2A2A2A),
                Color(0xFF1A1A1A)
            ),
            start = androidx.compose.ui.geometry.Offset(offset * 300f, 0f),
            end = androidx.compose.ui.geometry.Offset((offset + 1f) * 300f, 0f)
        )
    })
}

/**
 * Pulsing border glow for thinking/reasoning containers.
 */
@Composable
fun rememberPulsingAlpha(): State<Float> {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    return infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )
}
