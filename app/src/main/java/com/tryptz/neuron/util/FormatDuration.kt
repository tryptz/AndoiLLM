package com.tryptz.neuron.util

fun Long.formatDuration(): String {
    if (this < 0) return "calculating..."
    val hours = this / 3600
    val minutes = (this % 3600) / 60
    val seconds = this % 60
    return when {
        hours > 0 -> "${hours}h ${minutes}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
