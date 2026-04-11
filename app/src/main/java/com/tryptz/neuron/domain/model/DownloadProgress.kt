package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class DownloadProgress(
    val modelId: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val speedBytesPerSec: Long = 0,
    val isPaused: Boolean = false
) {
    val progress: Float get() = if (totalBytes > 0) bytesDownloaded.toFloat() / totalBytes else 0f
    val etaSeconds: Long get() = if (speedBytesPerSec > 0) (totalBytes - bytesDownloaded) / speedBytesPerSec else -1
}
