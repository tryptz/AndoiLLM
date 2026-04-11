@file:UseSerializers(IntRangeSerializer::class)

package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

object IntRangeSerializer : KSerializer<IntRange> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("IntRange", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: IntRange) {
        encoder.encodeString("${value.first}:${value.last}")
    }

    override fun deserialize(decoder: Decoder): IntRange {
        val raw = decoder.decodeString()
        val parts = raw.split(':', limit = 2)
        val start = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val end = parts.getOrNull(1)?.toIntOrNull() ?: start
        return start..end
    }
}

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
    val recommendationTag: String? = null
) {
    /** Convenience accessor for the raw string ID. */
    val id: String get() = modelId.raw
}
