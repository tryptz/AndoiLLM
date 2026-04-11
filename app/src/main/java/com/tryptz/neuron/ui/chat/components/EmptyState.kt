package com.tryptz.neuron.ui.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun EmptyState(
    hasModel: Boolean,
    hasInstalledModels: Boolean,
    onDownloadModel: () -> Unit,
    onSelectModel: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Neuron", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("On-device AI, completely private", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(32.dp))

        if (!hasInstalledModels) {
            Card(
                onClick = onDownloadModel,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Download, contentDescription = null)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Download Your First Model", style = MaterialTheme.typography.titleMedium)
                        Text("One tap to start chatting", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (!hasModel) {
            OutlinedButton(onClick = onSelectModel) { Text("Select a Model") }
        }
    }
}
