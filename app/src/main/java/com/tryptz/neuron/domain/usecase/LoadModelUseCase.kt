package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.data.local.datastore.SettingsDataStore
import com.tryptz.neuron.data.repository.ModelRepository
import com.tryptz.neuron.domain.model.InferenceSettings
import com.tryptz.neuron.inference.backend.InferenceEngine
import timber.log.Timber
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
        Timber.i("[op=load_model_resolve] modelId=$modelId ctx=${settings.contextLength} kv=${settings.kvCacheQuant} backend=${settings.backend}")

        // Try registry model first, then local model
        val descriptor = modelRepo.getDescriptorById(modelId)
        val modelPath = modelRepo.getModelPath(modelId)

        if (descriptor != null && modelPath != null) {
            Timber.i("[op=load_model_path] source=registry name=\"${descriptor.name}\" path=$modelPath")
            val result = inferenceEngine.loadModel(descriptor, modelPath, settings)
            if (result.isSuccess) settingsStore.setActiveModel(modelId)
            return result
        }

        // Check local models
        val localModel = modelRepo.getLocalModel(modelId)
        if (localModel == null) {
            Timber.e("[op=load_model_unknown] modelId=$modelId no_registry_match no_local_match")
            return Result.failure(IllegalArgumentException("Unknown model: $modelId"))
        }

        val localDescriptor = modelRepo.buildLocalDescriptor(localModel)
        Timber.i("[op=load_model_path] source=local name=\"${localModel.name}\" path=${localModel.filePath} size_bytes=${localModel.fileSizeBytes}")
        val result = inferenceEngine.loadModel(localDescriptor, localModel.filePath, settings)
        if (result.isSuccess) settingsStore.setActiveModel(modelId)
        return result
    }
}
