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
        // Try registry model first, then local model
        val descriptor = modelRepo.getDescriptorById(modelId)
        val modelPath = modelRepo.getModelPath(modelId)

        if (descriptor != null && modelPath != null) {
            val result = inferenceEngine.loadModel(descriptor, modelPath, settings)
            if (result.isSuccess) settingsStore.setActiveModel(modelId)
            return result
        }

        // Check local models
        val localModel = modelRepo.getLocalModel(modelId)
            ?: return Result.failure(IllegalArgumentException("Unknown model: $modelId"))

        val localDescriptor = modelRepo.buildLocalDescriptor(localModel)
        val result = inferenceEngine.loadModel(localDescriptor, localModel.filePath, settings)
        if (result.isSuccess) settingsStore.setActiveModel(modelId)
        return result
    }
}
