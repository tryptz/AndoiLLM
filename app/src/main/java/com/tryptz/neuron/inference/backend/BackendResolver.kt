package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.ModelDescriptor
import com.tryptz.neuron.domain.model.Quantization

/**
 * Resolves the optimal inference backend for a given model
 * based on its size, supported backends, quantization format,
 * and available hardware.
 *
 * Hexagon NPU constraints (llama.cpp GGML_HEXAGON):
 *   - Supported quants: Q4_0, Q8_0, FP16 (+ IQ4_NL in some builds)
 *   - Single HTP session ≈ 2 GB → models ≤ 4B params
 *   - K-type quants (Q4_K_M, Q4_K_S, etc.) are NOT supported on NPU
 */
object BackendResolver {

    /** Quantizations the Hexagon NPU HTP backend can execute. */
    private val npuCompatibleQuants = setOf(
        Quantization.Q4_0,
        Quantization.Q8_0,
        Quantization.INT4,  // maps to Q4_0 on HTP
        Quantization.INT8,  // maps to Q8_0 on HTP
        Quantization.FP16
    )

    fun resolve(descriptor: ModelDescriptor): InferenceBackend {
        val backends = descriptor.supportedBackends
        return when {
            InferenceBackend.NPU in backends
                && descriptor.ramRequiredMb <= 4000
                && isNpuCompatibleQuant(descriptor.quantization) -> InferenceBackend.NPU
            InferenceBackend.GPU in backends -> InferenceBackend.GPU
            else -> InferenceBackend.CPU
        }
    }

    /** Check if a quantization format can run on the Hexagon NPU. */
    fun isNpuCompatibleQuant(quant: Quantization): Boolean =
        quant in npuCompatibleQuants

    fun resolveThreadCount(): Int {
        val cores = Runtime.getRuntime().availableProcessors()
        return (cores - 2).coerceIn(2, 6) // leave 2 cores for UI
    }
}
