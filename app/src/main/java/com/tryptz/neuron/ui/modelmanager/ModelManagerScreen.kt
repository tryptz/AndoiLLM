package com.tryptz.neuron.ui.modelmanager

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.tryptz.neuron.data.local.entity.LocalModelEntity
import com.tryptz.neuron.domain.model.*
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
    val context = LocalContext.current

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        val fileName = cursor?.use {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) it.getString(nameIndex) else null
        } ?: "model.gguf"
        viewModel.onFileSelected(uri, fileName)
    }

    // Delete confirmation dialog (registry models)
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

    // Delete confirmation dialog (local models)
    state.deleteConfirmLocalId?.let { localId ->
        val local = state.localModels.find { it.id == localId }
        AlertDialog(
            onDismissRequest = { viewModel.dismissDeleteLocal() },
            title = { Text("Delete ${local?.name ?: "model"}?") },
            text = { Text("This will remove the imported model file from app storage.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteLocalModel(localId) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteLocal() }) { Text("Cancel") }
            }
        )
    }

    // Import configuration dialog
    if (state.showImportDialog) {
        ImportModelDialog(
            fileName = state.importFileName,
            name = state.importName,
            chatTemplate = state.importChatTemplate,
            contextLength = state.importContextLength,
            isImporting = state.isImporting,
            isDetecting = state.isDetecting,
            detectedArch = state.importArchitecture,
            detectedQuant = state.importQuantization,
            error = state.importError,
            onNameChange = viewModel::updateImportName,
            onTemplateChange = viewModel::updateImportChatTemplate,
            onContextLengthChange = viewModel::updateImportContextLength,
            onConfirm = viewModel::confirmImport,
            onDismiss = viewModel::dismissImportDialog
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

            // Import local model button
            item {
                OutlinedCard(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LightClick)
                        filePicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Import Local Model", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Load a GGUF model file from your device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Import",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Local models section
            if (state.localModels.isNotEmpty()) {
                item {
                    Text(
                        "Local Models",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(state.localModels, key = { "local_${it.id}" }) { local ->
                    LocalModelCard(
                        model = local,
                        onDelete = { viewModel.confirmDeleteLocal(local.id) },
                        modifier = Modifier.animateItem()
                    )
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

@Composable
private fun LocalModelCard(
    model: LocalModelEntity,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.FolderOpen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        buildString {
                            model.architecture?.let { append("$it · ") }
                            model.quantization?.let { append("$it · ") }
                            append(ChatTemplate.fromRaw(model.chatTemplate).name)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                StatItem("Size", model.fileSizeBytes.formatBytes())
                StatItem("Context", "${model.contextLength}")
                model.architecture?.let { StatItem("Arch", it) }
                model.quantization?.let { StatItem("Quant", it) }
            }

            Spacer(Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text("Imported") },
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportModelDialog(
    fileName: String,
    name: String,
    chatTemplate: ChatTemplate,
    contextLength: Int,
    isImporting: Boolean,
    isDetecting: Boolean,
    detectedArch: String?,
    detectedQuant: String?,
    error: String?,
    onNameChange: (String) -> Unit,
    onTemplateChange: (ChatTemplate) -> Unit,
    onContextLengthChange: (Int) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    var templateExpanded by remember { mutableStateOf(false) }
    var contextExpanded by remember { mutableStateOf(false) }
    val contextOptions = listOf(2048, 4096, 8192, 16384, 32768, 65536, 131072)

    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text("Import Model") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "File: $fileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Detected metadata banner
                if (isDetecting) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Detecting model settings...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (detectedArch != null || detectedQuant != null) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.primaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.AutoFixHigh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                buildString {
                                    append("Detected: ")
                                    listOfNotNull(detectedArch, detectedQuant)
                                        .joinTo(this, " · ")
                                },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = onNameChange,
                    label = { Text("Model Name") },
                    singleLine = true,
                    enabled = !isImporting,
                    modifier = Modifier.fillMaxWidth()
                )

                // Chat template selector
                ExposedDropdownMenuBox(
                    expanded = templateExpanded,
                    onExpandedChange = { if (!isImporting) templateExpanded = it }
                ) {
                    OutlinedTextField(
                        value = chatTemplate.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Chat Template") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = templateExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = templateExpanded,
                        onDismissRequest = { templateExpanded = false }
                    ) {
                        ChatTemplate.entries.forEach { template ->
                            DropdownMenuItem(
                                text = { Text(template.name) },
                                onClick = {
                                    onTemplateChange(template)
                                    templateExpanded = false
                                }
                            )
                        }
                    }
                }

                // Context length selector
                ExposedDropdownMenuBox(
                    expanded = contextExpanded,
                    onExpandedChange = { if (!isImporting) contextExpanded = it }
                ) {
                    OutlinedTextField(
                        value = "$contextLength",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Context Length") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = contextExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = contextExpanded,
                        onDismissRequest = { contextExpanded = false }
                    ) {
                        contextOptions.forEach { length ->
                            DropdownMenuItem(
                                text = { Text("$length") },
                                onClick = {
                                    onContextLengthChange(length)
                                    contextExpanded = false
                                }
                            )
                        }
                    }
                }

                if (isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "Copying model file...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !isImporting && !isDetecting && name.isNotBlank()) {
                Text("Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isImporting) {
                Text("Cancel")
            }
        }
    )
}
