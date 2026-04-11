package com.tryptz.neuron.ui.editor

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.ui.chat.components.CodeOutputPanel
import com.tryptz.neuron.ui.theme.CodeFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeEditorScreen(
    initialCode: String = "",
    initialLanguage: String = "js",
    codeExecutor: CodeExecutor,
    onSendToChat: (String) -> Unit = {},
    onBack: () -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current
    val clipboard = LocalClipboardManager.current

    var code by remember { mutableStateOf(TextFieldValue(initialCode)) }
    var fontSize by remember { mutableFloatStateOf(14f) }
    var output by remember { mutableStateOf<CodeOutput?>(null) }
    var isRunning by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var wordWrap by remember { mutableStateOf(true) }
    var showLineNumbers by remember { mutableStateOf(true) }

    // Undo/redo stacks
    val undoStack = remember { mutableStateListOf<TextFieldValue>() }
    val redoStack = remember { mutableStateListOf<TextFieldValue>() }

    val language = remember(initialLanguage) {
        CodeLanguage.entries.find { it.extension == initialLanguage } ?: CodeLanguage.JAVASCRIPT
    }

    // Run button animation
    val runIconRotation by animateFloatAsState(
        targetValue = if (isRunning) 360f else 0f,
        animationSpec = if (isRunning) infiniteRepeatable(tween(1000), RepeatMode.Restart)
                        else spring(stiffness = 400f, dampingRatio = 0.8f),
        label = "run_rotation"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(language.displayName, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    // Undo
                    IconButton(
                        onClick = {
                            if (undoStack.isNotEmpty()) {
                                redoStack.add(code)
                                code = undoStack.removeLast()
                            }
                        },
                        enabled = undoStack.isNotEmpty()
                    ) { Icon(Icons.Default.Undo, "Undo") }

                    // Redo
                    IconButton(
                        onClick = {
                            if (redoStack.isNotEmpty()) {
                                undoStack.add(code)
                                code = redoStack.removeLast()
                            }
                        },
                        enabled = redoStack.isNotEmpty()
                    ) { Icon(Icons.Default.Redo, "Redo") }

                    // Search
                    IconButton(onClick = { showSearch = !showSearch }) {
                        Icon(Icons.Default.Search, "Find")
                    }

                    // Copy
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(code.text))
                        haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                    }) { Icon(Icons.Default.ContentCopy, "Copy") }

                    // Run
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                        isRunning = true
                        // Execution would be launched via coroutine in a real impl
                    }) {
                        Icon(
                            Icons.Default.PlayArrow,
                            "Run",
                            tint = Color(0xFF3FB950),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Send back to chat
                    IconButton(onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                        onSendToChat(code.text)
                        onBack()
                    }) { Icon(Icons.AutoMirrored.Filled.Send, "Send to chat") }
                }
            )
        },
        bottomBar = {
            // Extra coding keys row
            Surface(tonalElevation = 3.dp) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    listOf("Tab", "{", "}", "(", ")", "[", "]", ";", ":", "=", "/", "\"", "'").forEach { key ->
                        SuggestionChip(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                val insert = if (key == "Tab") "    " else key
                                val newText = code.text.substring(0, code.selection.start) +
                                              insert +
                                              code.text.substring(code.selection.end)
                                undoStack.add(code)
                                redoStack.clear()
                                code = TextFieldValue(
                                    text = newText,
                                    selection = androidx.compose.ui.text.TextRange(code.selection.start + insert.length)
                                )
                            },
                            label = { Text(key, fontFamily = CodeFontFamily, fontSize = 13.sp) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Search bar
            AnimatedVisibility(
                visible = showSearch,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    placeholder = { Text("Find…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = {
                        IconButton(onClick = { showSearch = false; searchQuery = "" }) {
                            Icon(Icons.Default.Close, "Close search")
                        }
                    }
                )
            }

            // Editor area with line numbers
            Row(
                modifier = Modifier
                    .weight(1f)
                    .background(Color(0xFF0D1117))
                    .pointerInput(Unit) {
                        detectTransformGestures { _, _, zoom, _ ->
                            fontSize = (fontSize * zoom).coerceIn(8f, 28f)
                        }
                    }
            ) {
                // Line numbers
                if (showLineNumbers) {
                    val lineCount = code.text.count { it == '\n' } + 1
                    Column(
                        modifier = Modifier
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                    ) {
                        for (i in 1..lineCount) {
                            Text(
                                text = i.toString(),
                                style = TextStyle(
                                    fontFamily = CodeFontFamily,
                                    fontSize = fontSize.sp,
                                    color = Color(0xFF484F58)
                                )
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .width(1.dp)
                            .fillMaxHeight()
                            .background(Color(0xFF21262D))
                    )
                }

                // Code editor
                BasicTextField(
                    value = code,
                    onValueChange = { newValue ->
                        if (newValue.text != code.text) {
                            undoStack.add(code)
                            redoStack.clear()
                        }
                        code = newValue
                    },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(12.dp),
                    textStyle = TextStyle(
                        fontFamily = CodeFontFamily,
                        fontSize = fontSize.sp,
                        color = Color(0xFFC9D1D9)
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
            }

            // Output panel (bottom half if output exists)
            output?.let { result ->
                HorizontalDivider(color = Color(0xFF21262D))
                Box(
                    modifier = Modifier
                        .weight(0.5f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    CodeOutputPanel(output = result)
                }
            }
        }
    }
}
