package com.tryptz.neuron.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.ui.animation.MotionTokens
import com.tryptz.neuron.ui.settings.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPanel(
    settings: InferenceSettings,
    settingsLevel: SettingsLevel,
    activeModel: ModelDescriptor?,
    telemetry: DeviceTelemetry,
    onUpdateSettings: ((InferenceSettings) -> InferenceSettings) -> Unit,
    onSetLevel: (SettingsLevel) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close") } }
            )

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text("Search settings…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge
            )

            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Basic — always visible
                if (searchQuery.isBlank() || "temperature context system".contains(searchQuery, ignoreCase = true)) {
                    item { BasicSettings(settings, activeModel, onUpdateSettings) }
                }

                // Advanced — expandable
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Advanced", Modifier.weight(1f))
                        if (settingsLevel == SettingsLevel.BASIC) {
                            TextButton(onClick = { onSetLevel(SettingsLevel.INTERMEDIATE) }) { Text("Show") }
                        }
                    }
                }
                item {
                    AnimatedVisibility(
                        visible = settingsLevel >= SettingsLevel.INTERMEDIATE,
                        enter = MotionTokens.settingsExpand(),
                        exit = MotionTokens.settingsCollapse()
                    ) {
                        AdvancedSettings(settings, activeModel, onUpdateSettings)
                    }
                }

                // Expert — expandable
                item {
                    Spacer(Modifier.height(8.dp))
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SectionHeader("Expert", Modifier.weight(1f))
                        if (settingsLevel < SettingsLevel.EXPERT) {
                            TextButton(onClick = { onSetLevel(SettingsLevel.EXPERT) }) { Text("Show") }
                        }
                    }
                }
                item {
                    AnimatedVisibility(
                        visible = settingsLevel >= SettingsLevel.EXPERT,
                        enter = MotionTokens.settingsExpand(),
                        exit = MotionTokens.settingsCollapse()
                    ) {
                        ExpertSettings(settings, activeModel, telemetry, onUpdateSettings)
                    }
                }

                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}
