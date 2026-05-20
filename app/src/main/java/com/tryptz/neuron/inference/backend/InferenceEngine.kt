package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.inference.bridge.LlamaBridge
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Public contract for the on-device inference engine.
 * Consumers (ChatViewModel, use cases) depend on this interface;
 * the concrete implementation is [InferenceEngineImpl], bound in InferenceModule.
 */
interface InferenceEngine {
    val telemetry: StateFlow<DeviceTelemetry>
    val isModelLoaded: Boolean
    val loadedModel: ModelDescriptor?

    suspend fun loadModel(
        descriptor: ModelDescriptor,
        modelPath: String,
        settings: InferenceSettings
    ): Result<Unit>

    fun generateStream(
        messages: List<ChatMessage>,
        settings: InferenceSettings
    ): Flow<InferenceEvent>

    fun cancelGeneration()

    suspend fun unloadModel()
}

@Singleton
class InferenceEngineImpl @Inject constructor() : InferenceEngine {

    /**
     * Serializes [loadModel] and [unloadModel] so a concurrent model switch
     * cannot leak a multi-GB native mmap or unload a model mid-load.
     * NOTE: Mutex is non-reentrant — loadModel must call [unloadLocked], never [unloadModel].
     */
    private val modelLock = Mutex()

    private var currentModel: ModelHandle? = null

    /** Read by [cancelGeneration] from another thread; mutated by [generateStream]. */
    @Volatile
    private var activeCompletionHandle: Long = NO_COMPLETION

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    override val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    override val isModelLoaded: Boolean get() = currentModel != null
    override val loadedModel: ModelDescriptor? get() = currentModel?.descriptor

    override suspend fun loadModel(
        descriptor: ModelDescriptor,
        modelPath: String,
        settings: InferenceSettings
    ): Result<Unit> = modelLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                LlamaBridge.ensureLoaded()
                // Hold the lock once: unload the previous model inline rather than
                // calling the public unloadModel() (which would re-enter the mutex).
                unloadLocked()

                // Battery Saver forces CPU + small batch regardless of user
                // backend choice — saves the most power, accepts slower decode.
                val backend = when {
                    settings.batterySaver -> InferenceBackend.CPU
                    settings.backend == InferenceBackend.AUTO -> BackendResolver.resolve(descriptor)
                    else -> settings.backend
                }
                if (settings.batterySaver) {
                    Timber.i("[op=battery_saver_active] forcing backend=CPU")
                }

                val handle = LlamaBridge.loadModel(
                    modelPath = modelPath,
                    contextLength = settings.contextLength.coerceAtMost(descriptor.maxContext),
                    batchSize = settings.batchSize,
                    threadCount = if (settings.threadCount == 0) BackendResolver.resolveThreadCount() else settings.threadCount,
                    gpuLayers = if (backend == InferenceBackend.GPU) GPU_LAYERS_ALL else GPU_LAYERS_NONE,
                    useVulkan = backend == InferenceBackend.GPU,
                    kvCacheTypeQuant = kvCacheTypeFor(settings.kvCacheQuant)
                )

                require(handle != 0L) { "Failed to load model: ${descriptor.name}" }
                currentModel = ModelHandle(handle, descriptor, backend)
                Timber.i("Model loaded: ${descriptor.name} on $backend")
            }
        }
    }

    override fun generateStream(
        messages: List<ChatMessage>,
        settings: InferenceSettings
    ): Flow<InferenceEvent> = flow {
        val model = currentModel ?: run {
            emit(InferenceEvent.Error("No model loaded")); return@flow
        }

        val prompt = PromptFormatter.format(messages, model.descriptor, settings)
        Timber.i("[op=inference_start] model=\"${model.descriptor.name}\" messages=${messages.size} prompt_len=${prompt.length} temp=${settings.temperature} top_k=${settings.topK} top_p=${settings.topP}")
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
                // Battery saver clamps to 20 tok/s if user hasn't set a tighter cap.
                val effectiveCap = when {
                    settings.batterySaver && (settings.maxTokSecCap == 0 || settings.maxTokSecCap > 20) -> 20
                    else -> settings.maxTokSecCap
                }
                ThermalManager.applyTokSecCap(tokSec, effectiveCap)
            }
            Timber.i("[op=inference_complete] final_tok_sec=${LlamaBridge.getLastTokensPerSec()}")
            emit(InferenceEvent.Completed)
        } catch (e: CancellationException) {
            Timber.i("[op=inference_cancelled]")
            LlamaBridge.cancelCompletion(completionHandle); throw e
        } catch (e: Exception) {
            Timber.e(e, "[op=inference_error] err=${e.message}")
            emit(InferenceEvent.Error(e.message ?: "Inference error"))
        } finally {
            activeCompletionHandle = NO_COMPLETION
        }
    }.flowOn(Dispatchers.IO)

    override fun cancelGeneration() {
        // Snapshot the volatile once so the check and the call see the same handle.
        val handle = activeCompletionHandle
        if (handle != NO_COMPLETION) LlamaBridge.cancelCompletion(handle)
    }

    override suspend fun unloadModel() = modelLock.withLock {
        withContext(Dispatchers.IO) { unloadLocked() }
    }

    /** Actual unload work. Caller MUST hold [modelLock]. */
    private fun unloadLocked() {
        currentModel?.let {
            LlamaBridge.unloadModel(it.nativeHandle)
            currentModel = null
        }
    }

    companion object {
        /** Sentinel for "no active completion" native handle. */
        private const val NO_COMPLETION = 0L

        /** Offload all transformer layers to the GPU (llama.cpp treats large values as "all"). */
        const val GPU_LAYERS_ALL = 999

        /** Keep all layers on the CPU. */
        const val GPU_LAYERS_NONE = 0

        // KV-cache quantization codes expected by the native bridge.
        private const val KV_CACHE_FP16 = 0
        private const val KV_CACHE_Q8_0 = 1
        private const val KV_CACHE_Q4_0 = 2

        /** Maps a [Quantization] choice to the native KV-cache type code. */
        fun kvCacheTypeFor(quant: Quantization): Int = when (quant) {
            Quantization.FP16 -> KV_CACHE_FP16
            Quantization.Q8_0 -> KV_CACHE_Q8_0
            Quantization.Q4_0 -> KV_CACHE_Q4_0
            else -> KV_CACHE_Q8_0
        }
    }
}
