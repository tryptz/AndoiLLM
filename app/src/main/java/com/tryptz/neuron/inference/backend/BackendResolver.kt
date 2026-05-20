package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.ModelDescriptor

/**
 * Resolves the optimal inference backend for a given model
 * based on its size, supported backends, and available hardware.
 */
object BackendResolver {

    /**
     * Maximum model RAM footprint (MB) eligible for the NPU.
     * Larger models exceed the NPU's addressable working set and fall back to GPU/CPU.
     */
    const val NPU_MAX_RAM_MB = 4000

    /** Cores reserved for the UI thread and system work. */
    private const val RESERVED_CORES = 2
    private const val MIN_INFERENCE_THREADS = 2
    private const val MAX_INFERENCE_THREADS = 6

    fun resolve(descriptor: ModelDescriptor): InferenceBackend {
        val backends = descriptor.supportedBackends
        return when {
            InferenceBackend.NPU in backends && descriptor.ramRequiredMb <= NPU_MAX_RAM_MB -> InferenceBackend.NPU
            InferenceBackend.GPU in backends -> InferenceBackend.GPU
            else -> InferenceBackend.CPU
        }
    }

    fun resolveThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - RESERVED_CORES).coerceIn(MIN_INFERENCE_THREADS, MAX_INFERENCE_THREADS)
    }
}
