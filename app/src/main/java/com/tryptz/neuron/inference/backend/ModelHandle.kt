package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.ModelDescriptor

data class ModelHandle(
    val nativeHandle: Long,
    val descriptor: ModelDescriptor,
    val backend: InferenceBackend
)
