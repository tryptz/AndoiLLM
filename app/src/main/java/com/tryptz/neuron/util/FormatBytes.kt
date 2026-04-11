package com.tryptz.neuron.util

import java.text.DecimalFormat

fun Long.formatBytes(): String {
    if (this < 1024) return "$this B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = this.toDouble()
    var unitIndex = -1
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024
        unitIndex++
    }
    return DecimalFormat("#.#").format(value) + " " + units[unitIndex]
}
