package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class ModelCapabilities(
    val vision: Boolean = false,
    val audio: Boolean = false,
    val reasoning: Boolean = false,
    val functionCalling: Boolean = false,
    val structuredOutput: Boolean = false
)

@Immutable
@Serializable
data class ModelDescriptor(
    val modelId: ModelId,
    val name: String,
    val family: String,
    val architecture: ModelArchitecture = ModelArchitecture.DENSE,
    val totalParams: String,
    val activeParams: String? = null,
    val quantization: Quantization,
    val fileSizeMb: Int,
    val ramRequiredMb: Int,
    val maxContext: Int,
    val supportedBackends: List<InferenceBackend>,
    val capabilities: ModelCapabilities = ModelCapabilities(),
    val chatTemplate: ChatTemplate = ChatTemplate.CHATML,
    val estimatedTokSec: Map<InferenceBackend, IntRange> = emptyMap(),
    val huggingFaceRepo: String,
    val huggingFaceFile: String,
    val recommendationTag: String? = null,
    val localId: String? = null
) {
    /** Convenience accessor — uses [localId] for imported models, raw enum ID for registry models. */
    val id: String get() = localId ?: modelId.raw
}
