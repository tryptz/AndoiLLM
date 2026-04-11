package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

@Immutable
@Serializable
data class Conversation(
    val id: String,
    val title: String,
    val modelId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val presetId: String? = null
)
