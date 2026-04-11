package com.tryptz.neuron.util

import java.util.Locale

fun Int.formatMbAsGb(): String =
    if (this >= 1024) String.format(Locale.US, "%.1f GB", this / 1024f)
    else "$this MB"
