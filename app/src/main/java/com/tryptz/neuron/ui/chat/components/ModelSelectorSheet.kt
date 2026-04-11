package com.tryptz.neuron.ui.chat.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.util.formatMbAsGb

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorSheet(
    installedModels: List<ModelDescriptor>,
    activeModel: ModelDescriptor?,
    availableRamMb: Int,
    onSelectModel: (ModelDescriptor) -> Unit,
    onManageModels: () -> Unit,
    onDismiss: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                "Models",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Text(
                "Available RAM: ${availableRamMb.formatMbAsGb()}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(16.dp))

            if (installedModels.isEmpty()) {
                Card(
                    onClick = onManageModels,
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Download, null)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Download a Model", style = MaterialTheme.typography.titleMedium)
                            Text("Browse recommended models", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(installedModels) { model ->
                        val isActive = model.id == activeModel?.id
                        val borderColor by animateColorAsState(
                            targetValue = if (isActive) MaterialTheme.colorScheme.primary
                                          else MaterialTheme.colorScheme.outlineVariant,
                            label = "model_border"
                        )

                        OutlinedCard(
                            onClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onSelectModel(model)
                                onDismiss()
                            },
                            border = CardDefaults.outlinedCardBorder().copy(
                                brush = androidx.compose.ui.graphics.SolidColor(borderColor)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(model.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${model.totalParams} · ${model.quantization.label} · ${model.ramRequiredMb.formatMbAsGb()} RAM",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    model.recommendationTag?.let { tag ->
                                        Spacer(Modifier.height(4.dp))
                                        AssistChip(
                                            onClick = {},
                                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) },
                                            modifier = Modifier.height(24.dp)
                                        )
                                    }
                                }

                                if (isActive) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "Active",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }

                                if (!ModelRegistry_fitsInRam(model, availableRamMb)) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = "May not fit in RAM",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = onManageModels,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Tune, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Manage Models")
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// Helper to avoid importing ModelRegistry directly in UI
private fun ModelRegistry_fitsInRam(model: ModelDescriptor, availableMb: Int) =
    model.ramRequiredMb <= availableMb
