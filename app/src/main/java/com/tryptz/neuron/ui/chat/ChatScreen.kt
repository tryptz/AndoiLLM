package com.tryptz.neuron.ui.chat

import androidx.compose.animation.*
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tryptz.neuron.ui.animation.MotionTokens
import com.tryptz.neuron.ui.chat.components.*
import com.tryptz.neuron.ui.chat.viewmodel.ChatViewModel
import com.tryptz.neuron.ui.settings.SettingsPanel

@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onNavigateToModelManager: () -> Unit = {},
    onNavigateToEditor: (String, String) -> Unit = { _, _ -> }
) {
    val state by viewModel.uiState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val settingsLevel by viewModel.settingsLevel.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll on new content
    LaunchedEffect(state.messages.size, state.streamingContent) {
        if (state.messages.isNotEmpty() || state.streamingContent.isNotEmpty()) {
            listState.animateScrollToItem(
                (state.messages.size + if (state.isGenerating) 1 else 0).coerceAtLeast(0)
            )
        }
    }

    // Model selector bottom sheet
    if (state.showModelSelector) {
        ModelSelectorSheet(
            installedModels = state.installedModels,
            activeModel = state.activeModel,
            availableRamMb = state.availableRamMb,
            onSelectModel = { viewModel.selectModel(it.id) },
            onManageModels = onNavigateToModelManager,
            onDismiss = { viewModel.toggleModelSelector() }
        )
    }

    Scaffold(
        topBar = {
            ChatTopBar(
                activeModel = state.activeModel,
                isGenerating = state.isGenerating,
                telemetry = state.telemetry,
                tokSec = state.tokSec,
                onNewChat = viewModel::newConversation,
                onToggleModelSelector = viewModel::toggleModelSelector,
                onToggleSettings = viewModel::toggleSettings
            )
        },
        bottomBar = {
            ChatInputBar(
                capabilities = state.activeModel?.capabilities,
                isGenerating = state.isGenerating,
                onSend = { viewModel.sendMessage(it) },
                onCancel = viewModel::cancelGeneration,
                onCameraTap = {},
                onMicTap = {}
            )
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            if (state.messages.isEmpty() && !state.isGenerating) {
                EmptyState(
                    hasModel = state.activeModel != null,
                    hasInstalledModels = state.installedModels.isNotEmpty(),
                    onDownloadModel = onNavigateToModelManager,
                    onSelectModel = { viewModel.toggleModelSelector() }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(items = state.messages, key = { it.id }) { message ->
                        ChatBubble(
                            message = message,
                            onRunCode = { viewModel.executeCode(it) },
                            onEditCode = { block -> onNavigateToEditor(block.code, block.language.extension) },
                            modifier = Modifier.animateItem(
                                fadeInSpec = tween(300), fadeOutSpec = tween(200),
                                placementSpec = spring(stiffness = 200f, dampingRatio = 0.7f)
                            )
                        )
                    }
                    if (state.isGenerating) {
                        item(key = "streaming") {
                            StreamingBubble(
                                content = state.streamingContent,
                                thinkingContent = state.streamingThinking,
                                isThinking = state.isThinking,
                                tokSec = state.tokSec,
                                tokenCount = state.tokenCount
                            )
                        }
                    }
                }
            }

            state.error?.let { error ->
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    action = { TextButton(onClick = { viewModel.clearError() }) { Text("Dismiss") } }
                ) { Text(error) }
            }

            // Transient success Snackbar after a model loads — auto-dismisses in 3s.
            state.loadConfirmation?.let { msg ->
                LaunchedEffect(msg) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.clearLoadConfirmation()
                }
                Snackbar(
                    modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) { Text(msg) }
            }
        }
    }

    // Settings side sheet — emitted AFTER Scaffold so it overlays the chat
    // UI when visible. Previously this block sat above Scaffold and the
    // Scaffold drew on top, so tapping the settings icon "did nothing" from
    // the user's POV (state.showSettings flipped, panel rendered, but chat UI
    // covered it). Source order = z order for sibling composables.
    AnimatedVisibility(
        visible = state.showSettings,
        enter = MotionTokens.panelSlideIn(),
        exit = MotionTokens.panelSlideOut()
    ) {
        SettingsPanel(
            settings = settings, settingsLevel = settingsLevel,
            activeModel = state.activeModel, telemetry = state.telemetry,
            onUpdateSettings = viewModel::updateSettings,
            onSetLevel = viewModel::setSettingsLevel,
            onDismiss = { viewModel.toggleSettings() }
        )
    }

    // Modal load overlay — non-cancellable, full screen. Shown for the entire
    // GC-settle + mmap window so the user has continuous feedback.
    if (state.isLoadingModel) {
        AlertDialog(
            onDismissRequest = { /* loading is not cancellable mid-mmap */ },
            confirmButton = {},
            title = { Text("Loading model") },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator(modifier = Modifier.padding(vertical = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = state.loadingStatus ?: "Working…",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "This usually takes 10–30 seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )
    }
}
