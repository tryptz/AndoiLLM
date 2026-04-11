package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.ModelDescriptor

/**
 * Resolves the optimal inference backend for a given model
 * based on its size, supported backends, and available hardware.
 */
object BackendResolver {

    fun resolve(descriptor: ModelDescriptor): InferenceBackend {
        val backends = descriptor.supportedBackends
        return when {
            InferenceBackend.NPU in backends && descriptor.ramRequiredMb <= 4000 -> InferenceBackend.NPU
            InferenceBackend.GPU in backends -> InferenceBackend.GPU
            else -> InferenceBackend.CPU
        }
    }

    fun resolveThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 6) // leave 2 cores for UI
    }
}
