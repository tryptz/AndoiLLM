package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class InferenceSettings(
    val backend: InferenceBackend = InferenceBackend.AUTO,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val minP: Float = 0.05f,
    val repeatPenalty: Float = 1.1f,
    val contextLength: Int = 4096,
    val systemPrompt: String = "",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val maxThinkingTokens: Int = 1024,
    val threadCount: Int = 0,
    val npuPrecision: Quantization = Quantization.INT4,
    val batchSize: Int = 512,
    val kvCacheQuant: Quantization = Quantization.Q8_0,
    val thermalPolicy: ThermalPolicy = ThermalPolicy.SLOW_DOWN,
    val batterySaver: Boolean = false,
    val maxTokSecCap: Int = 0,
    val backgroundInference: Boolean = false,
    val wakeLock: Boolean = false,
    val codeTimeoutSec: Int = 10,
    val codeMemoryMb: Int = 64,
    val codeNetworkAllowed: Boolean = false
)
