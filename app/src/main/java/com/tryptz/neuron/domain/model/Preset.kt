package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Preset(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val modelId: ModelId?,
    val backend: InferenceBackend = InferenceBackend.AUTO,
    val temperature: Float = 0.7f,
    val topP: Float = 0.9f,
    val topK: Int = 40,
    val contextLength: Int = 4096,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.NONE,
    val batterySaver: Boolean = false,
    val maxTokSec: Int? = null
)
