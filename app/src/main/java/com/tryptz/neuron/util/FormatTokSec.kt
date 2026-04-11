package com.tryptz.neuron.util

import java.util.Locale

fun Float.formatTokSec(): String = String.format(Locale.US, "%.1f tok/s", this)
