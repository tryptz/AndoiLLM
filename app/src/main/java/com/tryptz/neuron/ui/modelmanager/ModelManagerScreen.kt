package com.tryptz.neuron.ui.modelmanager

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.ui.animation.MotionTokens
import com.tryptz.neuron.ui.modelmanager.viewmodel.ModelManagerViewModel
import com.tryptz.neuron.util.formatBytes
import com.tryptz.neuron.util.formatDuration
import com.tryptz.neuron.util.formatMbAsGb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    viewModel: ModelManagerViewModel = hiltViewModel(),
    onBack: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val haptic = LocalHapticFeedback.current

    // Delete confirmation dialog
    state.deleteConfirmModelId?.let { modelId ->
        val model = state.allModels.find { it.id == modelId }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDelete() },
            title = { Text("Delete ${model?.name ?: "model"}?") },
            text = { Text("This will free ${model?.fileSizeMb?.formatMbAsGb() ?: "space"} of storage.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteModel(modelId) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDelete() }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Models") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // RAM header
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Device Memory", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        val usedRam = state.totalRamMb - state.availableRamMb
                        LinearProgressIndicator(
                            progress = { usedRam.toFloat() / state.totalRamMb.coerceAtLeast(1) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.availableRamMb.formatMbAsGb()} available of ${state.totalRamMb.formatMbAsGb()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Recommended section
            if (state.recommendedModels.isNotEmpty()) {
                item {
                    Text("Recommended for Your Device", style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp))
                }
                items(state.recommendedModels, key = { "rec_${it.id}" }) { model ->
                    ModelCard(
                        model = model,
                        isInstalled = model.id in state.installedModelIds,
                        downloadProgress = state.downloads[model.id],
                        availableRamMb = state.availableRamMb,
                        onDownload = { viewModel.downloadModel(model.id) },
                        onCancelDownload = { viewModel.cancelDownload(model.id) },
                        onDelete = { viewModel.confirmDelete(model.id) },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            // All models
            item {
                Text("All Models", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(state.allModels.filter { it.recommendationTag == null }, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isInstalled = model.id in state.installedModelIds,
                    downloadProgress = state.downloads[model.id],
                    availableRamMb = state.availableRamMb,
                    onDownload = { viewModel.downloadModel(model.id) },
                    onCancelDownload = { viewModel.cancelDownload(model.id) },
                    onDelete = { viewModel.confirmDelete(model.id) },
                    modifier = Modifier.animateItem()
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelDescriptor,
    isInstalled: Boolean,
    downloadProgress: DownloadProgress?,
    availableRamMb: Int,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val fitsRam = model.ramRequiredMb <= availableRamMb

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(model.name, style = MaterialTheme.typography.titleMedium)
                        if (model.architecture == ModelArchitecture.MOE) {
                            Spacer(Modifier.width(6.dp))
                            AssistChip(
                                onClick = {},
                                label = { Text("MoE", style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.height(22.dp)
                            )
                        }
                    }
                    Text(
                        buildString {
                            append(model.totalParams)
                            model.activeParams?.let { append(" (${it} active)") }
                            append(" · ${model.quantization.label}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                model.recommendationTag?.let { tag ->
                    SuggestionChip(
                        onClick = {},
                        label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // Stats row
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("Size", "${model.fileSizeMb.formatMbAsGb()}")
                StatItem("RAM", "${model.ramRequiredMb.formatMbAsGb()}")
                model.estimatedTokSec.entries.firstOrNull()?.let { (backend, range) ->
                    StatItem("Speed", "${range.first}-${range.last} t/s", subtitle = backend.label)
                }
            }

            // Capabilities
            if (model.capabilities.let { it.vision || it.audio || it.reasoning || it.functionCalling }) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (model.capabilities.vision) CapBadge("Vision")
                    if (model.capabilities.audio) CapBadge("Audio")
                    if (model.capabilities.reasoning) CapBadge("Reasoning")
                    if (model.capabilities.functionCalling) CapBadge("Tools")
                }
            }

            if (!fitsRam && !isInstalled) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("May not fit in available RAM", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(Modifier.height(12.dp))

            // Action buttons
            when {
                downloadProgress != null -> {
                    // Downloading
                    val animatedProgress by animateFloatAsState(
                        targetValue = downloadProgress.progress,
                        animationSpec = spring(stiffness = 200f, dampingRatio = 0.7f),
                        label = "dl_progress"
                    )
                    Column {
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${(downloadProgress.progress * 100).toInt()}% · ${downloadProgress.speedBytesPerSec.formatBytes()}/s",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                "ETA: ${downloadProgress.etaSeconds.formatDuration()}",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        OutlinedButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                            onCancelDownload()
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text("Cancel")
                        }
                    }
                }
                isInstalled -> {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(
                            onClick = {},
                            label = { Text("Installed") },
                            leadingIcon = { Icon(Icons.Default.CheckCircle, null, Modifier.size(16.dp)) },
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                            onDelete()
                        }) {
                            Icon(Icons.Default.Delete, null, Modifier.size(16.dp))
                        }
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                            onDownload()
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Download, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Download (${model.fileSizeMb.formatMbAsGb()})")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, subtitle: String? = null) {
    Column {
        Text(value, style = MaterialTheme.typography.labelLarge)
        Text(
            subtitle?.let { "$label ($it)" } ?: label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CapBadge(text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}
