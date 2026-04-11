package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.inference.bridge.LlamaBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InferenceEngine @Inject constructor() {

    private var currentModel: ModelHandle? = null
    private var activeCompletionHandle: Long = 0
    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    val isModelLoaded: Boolean get() = currentModel != null
    val loadedModel: ModelDescriptor? get() = currentModel?.descriptor

    suspend fun loadModel(
        descriptor: ModelDescriptor,
        modelPath: String,
        settings: InferenceSettings
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            LlamaBridge.ensureLoaded()
            unloadModel()

            val backend = if (settings.backend == InferenceBackend.AUTO) {
                BackendResolver.resolve(descriptor)
            } else settings.backend

            val handle = LlamaBridge.loadModel(
                modelPath = modelPath,
                contextLength = settings.contextLength.coerceAtMost(descriptor.maxContext),
                batchSize = settings.batchSize,
                threadCount = if (settings.threadCount == 0) BackendResolver.resolveThreadCount() else settings.threadCount,
                gpuLayers = if (backend == InferenceBackend.GPU) 999 else 0,
                useVulkan = backend == InferenceBackend.GPU,
                kvCacheTypeQuant = when (settings.kvCacheQuant) {
                    Quantization.FP16 -> 0; Quantization.Q8_0 -> 1; Quantization.Q4_0 -> 2; else -> 1
                }
            )

            require(handle != 0L) {
                "Failed to load ${descriptor.name} on ${backend.label} backend. " +
                    "Try a different backend or quantization format."
            }
            currentModel = ModelHandle(handle, descriptor, backend)
            Timber.i("Model loaded: ${descriptor.name} on $backend")
        }
    }

    fun generateStream(
        messages: List<ChatMessage>,
        settings: InferenceSettings
    ): Flow<InferenceEvent> = flow {
        val model = currentModel ?: run {
            emit(InferenceEvent.Error("No model loaded")); return@flow
        }

        val prompt = PromptFormatter.format(messages, model.descriptor, settings)
        emit(InferenceEvent.Started)

        val completionHandle = LlamaBridge.startCompletion(
            handle = model.nativeHandle, prompt = prompt,
            temperature = settings.temperature, topP = settings.topP,
            topK = settings.topK, minP = settings.minP,
            repeatPenalty = settings.repeatPenalty,
            maxTokens = settings.contextLength
        )
        activeCompletionHandle = completionHandle

        try {
            var inThinking = false
            while (currentCoroutineContext().isActive) {
                val token = LlamaBridge.getNextToken(completionHandle) ?: break

                if (model.descriptor.capabilities.reasoning) {
                    when {
                        token.contains("<think>") -> { inThinking = true; continue }
                        token.contains("</think>") -> { inThinking = false; continue }
                        inThinking -> { emit(InferenceEvent.ThinkingToken(token)); continue }
                    }
                }

                val tokSec = LlamaBridge.getLastTokensPerSec()
                emit(InferenceEvent.Token(token, tokSec))

                val cpuTemp = LlamaBridge.getCpuTemperature()
                _telemetry.value = _telemetry.value.copy(
                    currentTokSec = tokSec,
                    ramUsedMb = LlamaBridge.getModelRamUsageMb(model.nativeHandle),
                    cpuTempCelsius = cpuTemp,
                    thermalState = ThermalManager.classify(cpuTemp)
                )

                ThermalManager.applyThrottling(settings.thermalPolicy, cpuTemp)
                ThermalManager.applyTokSecCap(tokSec, settings.maxTokSecCap)
            }
            emit(InferenceEvent.Completed)
        } catch (e: CancellationException) {
            LlamaBridge.cancelCompletion(completionHandle); throw e
        } catch (e: Exception) {
            emit(InferenceEvent.Error(e.message ?: "Inference error"))
        } finally {
            activeCompletionHandle = 0
        }
    }.flowOn(Dispatchers.IO)

    fun cancelGeneration() {
        if (activeCompletionHandle != 0L) LlamaBridge.cancelCompletion(activeCompletionHandle)
    }

    suspend fun unloadModel() = withContext(Dispatchers.IO) {
        currentModel?.let {
            LlamaBridge.unloadModel(it.nativeHandle)
            currentModel = null
        }
    }
}
