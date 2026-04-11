package com.tryptz.neuron.ui.chat.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.ui.animation.MotionTokens
import com.tryptz.neuron.ui.animation.rememberPulsingAlpha

@Composable
fun ChatBubble(
    message: ChatMessage,
    onRunCode: (CodeBlock) -> Unit,
    onEditCode: (CodeBlock) -> Unit,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == MessageRole.USER
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        // Thinking section (for reasoning models)
        if (!message.thinkingContent.isNullOrBlank()) {
            ThinkingSection(content = message.thinkingContent!!)
            Spacer(Modifier.height(4.dp))
        }

        // Message bubble
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.widthIn(max = 320.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer
                           else MaterialTheme.colorScheme.onSurface
                )

                // Token stats for assistant messages
                if (!isUser && message.tokensPerSec != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${message.tokenCount} tokens · %.1f t/s".format(message.tokensPerSec),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // Code blocks (rendered below the bubble)
        for (block in message.codeBlocks) {
            Spacer(Modifier.height(8.dp))
            CodeBlockCard(
                codeBlock = block,
                onRun = { onRunCode(block) },
                onEdit = { onEditCode(block) },
                onCopy = {
                    clipboard.setText(AnnotatedString(block.code))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            )
        }
    }
}

@Composable
fun ThinkingSection(content: String) {
    var expanded by remember { mutableStateOf(false) }
    val pulseAlpha by rememberPulsingAlpha()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = pulseAlpha)
        ),
        onClick = { expanded = !expanded },
        modifier = Modifier.widthIn(max = 320.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Psychology,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Thinking",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = MotionTokens.settingsExpand(),
                exit = MotionTokens.settingsCollapse()
            ) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}

@Composable
fun CodeBlockCard(
    codeBlock: CodeBlock,
    onRun: () -> Unit,
    onEdit: () -> Unit,
    onCopy: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val isRunnable = codeBlock.language in listOf(
        CodeLanguage.JAVASCRIPT, CodeLanguage.PYTHON, CodeLanguage.BASH, CodeLanguage.HTML
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF0D1117) // GitHub-style dark code bg
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column {
            // Header with language + action buttons
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161B22))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = codeBlock.language.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8B949E)
                )

                Row {
                    if (isRunnable) {
                        IconButton(
                            onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onRun() },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Run",
                                tint = Color(0xFF3FB950),
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onEdit() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.Edit, "Edit", tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = onCopy,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Default.ContentCopy, "Copy", tint = Color(0xFF8B949E), modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Code content
            Text(
                text = codeBlock.code,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = com.tryptz.neuron.ui.theme.CodeFontFamily
                ),
                color = Color(0xFFC9D1D9),
                modifier = Modifier.padding(12.dp)
            )

            // Output panel
            codeBlock.output?.let { output ->
                AnimatedVisibility(
                    visible = true,
                    enter = expandVertically(animationSpec = spring(stiffness = 200f, dampingRatio = 0.7f)) + fadeIn()
                ) {
                    CodeOutputPanel(output = output)
                }
            }
        }
    }
}

@Composable
fun CodeOutputPanel(output: CodeOutput) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF0D1117))
            .padding(12.dp)
    ) {
        HorizontalDivider(color = Color(0xFF21262D))
        Spacer(Modifier.height(8.dp))

        // Error
        output.error?.let { error ->
            Surface(
                color = Color(0xFF3D1117),
                shape = RoundedCornerShape(6.dp)
            ) {
                Text(
                    text = "${error.type}: ${error.message}",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.tryptz.neuron.ui.theme.CodeFontFamily),
                    color = Color(0xFFF85149),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        // stdout
        if (output.stdout.isNotBlank()) {
            Text(
                text = output.stdout,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.tryptz.neuron.ui.theme.CodeFontFamily),
                color = Color(0xFFC9D1D9)
            )
        }

        // Return value
        output.returnValue?.let { rv ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = "→ $rv",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = com.tryptz.neuron.ui.theme.CodeFontFamily),
                color = Color(0xFF79C0FF)
            )
        }

        // Footer
        Spacer(Modifier.height(4.dp))
        Text(
            text = "${output.executionTimeMs}ms · ${(output.memoryUsedBytes / 1024)}KB",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF484F58)
        )
    }
}
