package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.data.local.datastore.SettingsDataStore
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.domain.model.InferenceSettings
import com.tryptz.neuron.inference.backend.InferenceEngine
import javax.inject.Inject

/**
 * Loads a model into the inference engine, updating the active
 * model preference in settings.
 */
class LoadModelUseCase @Inject constructor(
    private val modelRepo: ModelRepository,
    private val inferenceEngine: InferenceEngine,
    private val settingsStore: SettingsDataStore
) {
    suspend operator fun invoke(
        modelId: String,
        settings: InferenceSettings
    ): Result<Unit> {
        val descriptor = modelRepo.getDescriptorById(modelId)
            ?: return Result.failure(IllegalArgumentException("Unknown model: $modelId"))

        val modelPath = modelRepo.getModelPath(modelId)
            ?: return Result.failure(IllegalStateException("Model not installed: $modelId"))

        val result = inferenceEngine.loadModel(descriptor, modelPath, settings)

        if (result.isSuccess) {
            settingsStore.setActiveModel(modelId)
        }

        return result
    }
}
