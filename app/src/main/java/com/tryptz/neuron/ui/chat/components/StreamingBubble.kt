package com.tryptz.neuron.ui.chat.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.ui.animation.TypingIndicator
import com.tryptz.neuron.ui.animation.rememberPulsingAlpha

@Composable
fun StreamingBubble(
    content: String,
    thinkingContent: String,
    isThinking: Boolean,
    tokSec: Float,
    tokenCount: Int
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start
    ) {
        // Thinking indicator
        if (isThinking && thinkingContent.isNotBlank()) {
            val pulseAlpha by rememberPulsingAlpha()
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.primary.copy(alpha = pulseAlpha)
                ),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp).animateContentSize(
                    animationSpec = spring(stiffness = 200f, dampingRatio = 0.7f)
                )) {
                    Text(
                        "Thinking…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = thinkingContent.takeLast(200),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 4
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }

        // Main response bubble
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 4.dp, bottomEnd = 16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp).animateContentSize(
                animationSpec = spring(stiffness = 200f, dampingRatio = 0.7f)
            )) {
                if (content.isEmpty() && !isThinking) {
                    TypingIndicator()
                } else {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Live stats
                if (content.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "$tokenCount tokens · %.1f t/s".format(tokSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}
