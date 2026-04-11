package com.tryptz.neuron.util

import com.tryptz.neuron.domain.model.InferenceSettings
import com.tryptz.neuron.domain.model.ModelDescriptor

/**
 * Input validation for inference parameters.
 * Ensures settings are within hardware-safe bounds
 * before passing to the native engine.
 */
object Validation {

    fun validateSettings(
        settings: InferenceSettings,
        model: ModelDescriptor?
    ): List<String> {
        val errors = mutableListOf<String>()

        if (settings.temperature < 0f || settings.temperature > 2f) {
            errors += "Temperature must be between 0.0 and 2.0"
        }
        if (settings.topP < 0f || settings.topP > 1f) {
            errors += "Top P must be between 0.0 and 1.0"
        }
        if (settings.topK < 1 || settings.topK > 200) {
            errors += "Top K must be between 1 and 200"
        }
        if (settings.contextLength < 128) {
            errors += "Context length must be at least 128"
        }

        model?.let { m ->
            if (settings.contextLength > m.maxContext) {
                errors += "Context length ${settings.contextLength} exceeds model max ${m.maxContext}"
            }
            if (settings.backend != com.tryptz.neuron.domain.model.InferenceBackend.AUTO &&
                settings.backend !in m.supportedBackends) {
                errors += "Backend ${settings.backend.label} not supported by ${m.name}"
            }
        }

        if (settings.threadCount < 0 || settings.threadCount > 8) {
            errors += "Thread count must be between 0 (auto) and 8"
        }
        if (settings.batchSize < 1 || settings.batchSize > 4096) {
            errors += "Batch size must be between 1 and 4096"
        }
        if (settings.codeTimeoutSec < 1 || settings.codeTimeoutSec > 300) {
            errors += "Code timeout must be between 1 and 300 seconds"
        }
        if (settings.codeMemoryMb < 8 || settings.codeMemoryMb > 512) {
            errors += "Code memory limit must be between 8 and 512 MB"
        }

        return errors
    }

    fun modelFitsInRam(model: ModelDescriptor, availableRamMb: Int): Boolean =
        model.ramRequiredMb <= availableRamMb
}
