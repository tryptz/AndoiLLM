package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.inference.core.NeuronCore
import com.tryptz.neuron.core.Backend as CoreBackend
import com.tryptz.neuron.core.BackendRequest
import com.tryptz.neuron.core.ChatTemplate as CoreChatTemplate
import com.tryptz.neuron.core.GenerationRequest
import com.tryptz.neuron.core.KvCacheQuant
import com.tryptz.neuron.core.LoadReport
import com.tryptz.neuron.core.Message as CoreMessage
import com.tryptz.neuron.core.ModelConfig
import com.tryptz.neuron.core.NeuronException
import com.tryptz.neuron.core.ReasoningEffort as CoreReasoningEffort
import com.tryptz.neuron.core.Role as CoreRole
import com.tryptz.neuron.core.SamplingParams
import com.tryptz.neuron.core.StopReason
import com.tryptz.neuron.core.ThermalPolicy as CoreThermalPolicy
import com.tryptz.neuron.core.TokenSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

    /** Backend the loaded model actually runs on (may differ from the request). */
    val loadedBackend: InferenceBackend?

    /** Backends that have a live ggml device on this phone. */
    suspend fun availableBackends(): List<InferenceBackend>

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

/** What is loaded right now, as reported by the Rust core. */
private data class LoadedModelState(
    val descriptor: ModelDescriptor,
    val backend: InferenceBackend,
    val report: LoadReport
)

/**
 * Kotlin façade over the Rust core (`libneuron.so`). Prompt formatting,
 * sampling, thermal throttling, backend selection and decoding all happen in
 * Rust; this class maps domain types, owns coroutine plumbing, and keeps the
 * telemetry flow the UI observes.
 */
@Singleton
class InferenceEngineImpl @Inject constructor(
    private val core: NeuronCore
) : InferenceEngine {

    /**
     * Serializes load/unload/generate so a model switch cannot free a context
     * while a decode is in flight. Non-reentrant: loadModel uses [unloadLocked].
     */
    private val modelLock = Mutex()

    @Volatile
    private var current: LoadedModelState? = null

    private val _telemetry = MutableStateFlow(DeviceTelemetry())
    override val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    override val isModelLoaded: Boolean get() = current != null
    override val loadedModel: ModelDescriptor? get() = current?.descriptor
    override val loadedBackend: InferenceBackend? get() = current?.backend

    override suspend fun availableBackends(): List<InferenceBackend> = withContext(Dispatchers.IO) {
        core.engine().availableBackends().map { it.toDomain() }
    }

    override suspend fun loadModel(
        descriptor: ModelDescriptor,
        modelPath: String,
        settings: InferenceSettings
    ): Result<Unit> = modelLock.withLock {
        withContext(Dispatchers.IO) {
            runCatching {
                val engine = core.engine()
                unloadLocked()

                // Battery Saver forces the CPU regardless of the user's choice:
                // lowest power draw, accepts slower decode.
                val request = when {
                    settings.batterySaver -> BackendRequest.CPU
                    else -> settings.backend.toRequest()
                }
                if (settings.batterySaver) Timber.i("[op=battery_saver_active] forcing backend=CPU")

                val config = ModelConfig(
                    path = modelPath,
                    contextLength = settings.contextLength.coerceAtMost(descriptor.maxContext).toUInt(),
                    batchSize = settings.batchSize.toUInt(),
                    threads = settings.threadCount.coerceAtLeast(0).toUInt(),
                    backend = request,
                    kvCache = settings.kvCacheQuant.toKvCache(),
                    modelRamMb = descriptor.ramRequiredMb.toUInt(),
                    useMmap = true
                )
                val report = engine.loadModel(config)
                val backend = report.backend.toDomain()
                current = LoadedModelState(descriptor, backend, report)
                _telemetry.value = DeviceTelemetry()
                Timber.i(
                    "[op=model_loaded] name=\"${descriptor.name}\" backend=$backend " +
                        "device=${report.deviceName} reason=\"${report.backendReason}\" " +
                        "n_ctx=${report.nCtx} layers=${report.nLayer} ms=${report.loadMs}"
                )
            }.recoverCatching { e ->
                throw IllegalStateException(e.toUserMessage(descriptor), e)
            }
        }
    }

    override fun generateStream(
        messages: List<ChatMessage>,
        settings: InferenceSettings
    ): Flow<InferenceEvent> = channelFlow {
        // Hold modelLock for the whole stream: a concurrent unload must wait
        // until the Rust generate() call returns. cancelGeneration() does not
        // take the lock, so cancel still unblocks the loop promptly.
        modelLock.withLock { generateLocked(messages, settings) }
    }
        // Unbounded: the Rust decode loop calls back on this thread and must never
        // block on a full buffer while the UI thread drains tokens.
        .buffer(Channel.UNLIMITED)
        .flowOn(Dispatchers.IO)

    private suspend fun ProducerScope<InferenceEvent>.generateLocked(
        messages: List<ChatMessage>,
        settings: InferenceSettings
    ) {
        val model = current ?: run { send(InferenceEvent.Error("No model loaded")); return }
        val engine = core.engine()

        val request = GenerationRequest(
            messages = messages.map { CoreMessage(it.role.toCore(), it.content) },
            systemPrompt = settings.systemPrompt,
            template = model.descriptor.chatTemplate.toCore(),
            reasoning = settings.reasoningEffort.toCore(),
            reasoningCapable = model.descriptor.capabilities.reasoning,
            sampling = SamplingParams(
                temperature = settings.temperature,
                topP = settings.topP,
                topK = settings.topK,
                minP = settings.minP,
                repeatPenalty = settings.repeatPenalty,
                penaltyLastN = 64,
                seed = null
            ),
            maxTokens = 0u, // bounded by the context window
            thermalPolicy = settings.thermalPolicy.toCore(),
            tokSecCap = effectiveTokSecCap(settings).toUInt()
        )
        Timber.i(
            "[op=inference_start] model=\"${model.descriptor.name}\" backend=${model.backend} " +
                "messages=${messages.size} temp=${settings.temperature} top_k=${settings.topK} top_p=${settings.topP}"
        )
        send(InferenceEvent.Started)

        // The Rust side streams through this callback on the calling (IO) thread.
        // <think> handling stays here: it is a presentation concern.
        val sink = object : TokenSink {
            private var inThinking = false
            override fun onToken(text: String, tokSec: Float): Boolean {
                if (!isActive) return false
                var handled = false
                if (model.descriptor.capabilities.reasoning) {
                    when {
                        text.contains("<think>") -> { inThinking = true; handled = true }
                        text.contains("</think>") -> { inThinking = false; handled = true }
                        inThinking -> { trySend(InferenceEvent.ThinkingToken(text)); handled = true }
                    }
                }
                if (!handled) trySend(InferenceEvent.Token(text, tokSec))
                publishTelemetry(engine.telemetry())
                return isActive
            }
        }

        try {
            val summary = engine.generate(request, sink)
            Timber.i(
                "[op=inference_complete] tokens=${summary.tokens} prompt_tokens=${summary.promptTokens} " +
                    "avg_tok_sec=${summary.avgTokSec} ms=${summary.elapsedMs} stop=${summary.stop}"
            )
            when (summary.stop) {
                StopReason.CANCELLED, StopReason.SINK_STOPPED -> Timber.i("[op=inference_cancelled]")
                else -> {}
            }
            send(InferenceEvent.Completed)
        } catch (e: CancellationException) {
            Timber.i("[op=inference_cancelled]")
            engine.cancel()
            throw e
        } catch (e: NeuronException) {
            Timber.e(e, "[op=inference_error] err=${e.message}")
            send(InferenceEvent.Error(e.message ?: "Inference error"))
        } catch (e: Exception) {
            Timber.e(e, "[op=inference_error] err=${e.message}")
            send(InferenceEvent.Error(e.message ?: "Inference error"))
        }
    }

    /**
     * RAM/battery come from DeviceMonitor; the engine contributes what only it
     * knows: decode speed, SoC temperature as sampled in the decode loop, and
     * the derived thermal state.
     */
    private fun publishTelemetry(t: com.tryptz.neuron.core.Telemetry) {
        _telemetry.value = _telemetry.value.copy(
            currentTokSec = t.tokSec,
            cpuTempCelsius = t.cpuTempC,
            thermalState = t.thermalState.toDomain()
        )
    }

    override fun cancelGeneration() {
        // Lock-free on the Rust side: safe to call from any thread at any time.
        runCatching { core.engine().cancel() }
    }

    override suspend fun unloadModel() = modelLock.withLock {
        withContext(Dispatchers.IO) { unloadLocked() }
    }

    /** Caller MUST hold [modelLock]. */
    private fun unloadLocked() {
        if (current != null) {
            core.engine().unloadModel()
            current = null
            _telemetry.value = DeviceTelemetry()
            Timber.i("[op=model_unloaded]")
        }
    }

    private fun effectiveTokSecCap(settings: InferenceSettings): Int = when {
        // Battery saver clamps to 20 tok/s unless the user set a tighter cap.
        settings.batterySaver && (settings.maxTokSecCap == 0 || settings.maxTokSecCap > 20) -> 20
        else -> settings.maxTokSecCap
    }

    private fun Throwable.toUserMessage(descriptor: ModelDescriptor): String = when (this) {
        is NeuronException.ModelNotFound -> "Model file for ${descriptor.name} is missing. Re-download it."
        is NeuronException.ModelLoad -> "Failed to load ${descriptor.name}: ${message.orEmpty()}"
        is NeuronException.BackendUnavailable -> "Inference backend unavailable: ${message.orEmpty()}"
        is NeuronException -> message ?: "Failed to load ${descriptor.name}"
        else -> message ?: "Failed to load ${descriptor.name}"
    }
}

// ───────────────────────── domain ⇄ core mapping ─────────────────────────

internal fun InferenceBackend.toRequest(): BackendRequest = when (this) {
    InferenceBackend.NPU -> BackendRequest.HEXAGON_NPU
    InferenceBackend.GPU -> BackendRequest.ADRENO_GPU
    InferenceBackend.CPU -> BackendRequest.CPU
    InferenceBackend.AUTO -> BackendRequest.AUTO
}

internal fun CoreBackend.toDomain(): InferenceBackend = when (this) {
    CoreBackend.HEXAGON_NPU -> InferenceBackend.NPU
    CoreBackend.ADRENO_GPU -> InferenceBackend.GPU
    CoreBackend.CPU -> InferenceBackend.CPU
}

internal fun Quantization.toKvCache(): KvCacheQuant = when (this) {
    Quantization.Q4_0, Quantization.INT4, Quantization.INT2 -> KvCacheQuant.Q4_0
    Quantization.FP16 -> KvCacheQuant.F16
    else -> KvCacheQuant.Q8_0
}

internal fun ChatTemplate.toCore(): CoreChatTemplate = when (this) {
    ChatTemplate.CHATML -> CoreChatTemplate.CHAT_ML
    ChatTemplate.LLAMA3 -> CoreChatTemplate.LLAMA3
    ChatTemplate.GEMMA -> CoreChatTemplate.GEMMA
    ChatTemplate.PHI -> CoreChatTemplate.PHI
    ChatTemplate.MISTRAL -> CoreChatTemplate.MISTRAL
}

internal fun MessageRole.toCore(): CoreRole = when (this) {
    MessageRole.SYSTEM -> CoreRole.SYSTEM
    MessageRole.USER -> CoreRole.USER
    MessageRole.ASSISTANT -> CoreRole.ASSISTANT
}

internal fun ReasoningEffort.toCore(): CoreReasoningEffort = when (this) {
    ReasoningEffort.NONE -> CoreReasoningEffort.NONE
    ReasoningEffort.LOW -> CoreReasoningEffort.LOW
    ReasoningEffort.MEDIUM -> CoreReasoningEffort.MEDIUM
    ReasoningEffort.HIGH -> CoreReasoningEffort.HIGH
}

internal fun ThermalPolicy.toCore(): CoreThermalPolicy = when (this) {
    ThermalPolicy.PAUSE -> CoreThermalPolicy.PAUSE
    ThermalPolicy.SLOW_DOWN -> CoreThermalPolicy.SLOW_DOWN
    ThermalPolicy.IGNORE -> CoreThermalPolicy.IGNORE
}

internal fun com.tryptz.neuron.core.ThermalState.toDomain(): ThermalState = when (this) {
    com.tryptz.neuron.core.ThermalState.NOMINAL -> ThermalState.NOMINAL
    com.tryptz.neuron.core.ThermalState.WARM -> ThermalState.WARM
    com.tryptz.neuron.core.ThermalState.HOT -> ThermalState.HOT
    com.tryptz.neuron.core.ThermalState.CRITICAL -> ThermalState.CRITICAL
}
