package com.tryptz.neuron.data.model

import com.tryptz.neuron.domain.model.*

/**
 * Curated catalog of models known to run on Snapdragon 8 Elite Gen 5.
 *
 * Every entry uses [ModelId] — presets, settings, and UI all reference
 * the enum, so renaming an ID is a compile-time–safe refactor.
 */
object ModelRegistry {

    val models: List<ModelDescriptor> = listOf(
        ModelDescriptor(
            modelId = ModelId.GEMMA4_E2B, name = "Gemma 4 E2B", family = "gemma4",
            architecture = ModelArchitecture.MOE,
            totalParams = "5.1B", activeParams = "2.3B",
            quantization = Quantization.INT4, fileSizeMb = 2800, ramRequiredMb = 3000,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(vision = true, audio = true, functionCalling = true, structuredOutput = true),
            chatTemplate = ChatTemplate.GEMMA,
            estimatedTokSec = mapOf(InferenceBackend.NPU to 150..220, InferenceBackend.GPU to 40..65, InferenceBackend.CPU to 20..35),
            huggingFaceRepo = "google/gemma-4-e2b-it-gguf",
            huggingFaceFile = "gemma-4-e2b-it-Q4_K_M.gguf",
            recommendationTag = "Best for Speed"
        ),
        ModelDescriptor(
            modelId = ModelId.GEMMA4_E4B, name = "Gemma 4 E4B", family = "gemma4",
            architecture = ModelArchitecture.MOE,
            totalParams = "8B", activeParams = "4B",
            quantization = Quantization.INT4, fileSizeMb = 4200, ramRequiredMb = 4500,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(vision = true, audio = true, functionCalling = true, structuredOutput = true),
            chatTemplate = ChatTemplate.GEMMA,
            estimatedTokSec = mapOf(InferenceBackend.NPU to 100..150, InferenceBackend.GPU to 25..45, InferenceBackend.CPU to 12..22),
            huggingFaceRepo = "google/gemma-4-e4b-it-gguf",
            huggingFaceFile = "gemma-4-e4b-it-Q4_K_M.gguf",
            recommendationTag = "Best for Vision"
        ),
        ModelDescriptor(
            modelId = ModelId.LLAMA32_3B, name = "Llama 3.2 3B", family = "llama3",
            totalParams = "3.2B", quantization = Quantization.INT4,
            fileSizeMb = 1800, ramRequiredMb = 2200, maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            chatTemplate = ChatTemplate.LLAMA3,
            estimatedTokSec = mapOf(InferenceBackend.NPU to 120..180, InferenceBackend.GPU to 35..55, InferenceBackend.CPU to 25..40),
            huggingFaceRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            huggingFaceFile = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            recommendationTag = "Best for Conversations"
        ),
        ModelDescriptor(
            modelId = ModelId.LLAMA32_1B, name = "Llama 3.2 1B", family = "llama3",
            totalParams = "1.2B", quantization = Quantization.INT4,
            fileSizeMb = 750, ramRequiredMb = 1000, maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            chatTemplate = ChatTemplate.LLAMA3,
            estimatedTokSec = mapOf(InferenceBackend.NPU to 200..300, InferenceBackend.GPU to 60..90, InferenceBackend.CPU to 40..60),
            huggingFaceRepo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            huggingFaceFile = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        ),
        ModelDescriptor(
            modelId = ModelId.QWEN25_7B, name = "Qwen 2.5 7B", family = "qwen2",
            totalParams = "7.6B", quantization = Quantization.Q4_K_M,
            fileSizeMb = 4600, ramRequiredMb = 5200, maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(structuredOutput = true),
            chatTemplate = ChatTemplate.CHATML,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 20..35, InferenceBackend.CPU to 8..15),
            huggingFaceRepo = "Qwen/Qwen2.5-7B-Instruct-GGUF",
            huggingFaceFile = "qwen2.5-7b-instruct-q4_k_m.gguf",
            recommendationTag = "Best for Quality"
        ),
        ModelDescriptor(
            modelId = ModelId.PHI4_MINI, name = "Phi-4 Mini", family = "phi",
            totalParams = "3.8B", quantization = Quantization.INT4,
            fileSizeMb = 2200, ramRequiredMb = 2500, maxContext = 16384,
            supportedBackends = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(reasoning = true, structuredOutput = true),
            chatTemplate = ChatTemplate.PHI,
            estimatedTokSec = mapOf(InferenceBackend.NPU to 100..150, InferenceBackend.GPU to 30..50, InferenceBackend.CPU to 18..30),
            huggingFaceRepo = "bartowski/phi-4-mini-instruct-GGUF",
            huggingFaceFile = "phi-4-mini-instruct-Q4_K_M.gguf"
        ),
        ModelDescriptor(
            modelId = ModelId.MISTRAL_SMALL4, name = "Mistral Small 4", family = "mistral",
            architecture = ModelArchitecture.MOE,
            totalParams = "119B", activeParams = "6B",
            quantization = Quantization.INT4, fileSizeMb = 4000, ramRequiredMb = 4500,
            maxContext = 32768,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(reasoning = true, functionCalling = true, structuredOutput = true),
            chatTemplate = ChatTemplate.MISTRAL,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 18..30, InferenceBackend.CPU to 6..12),
            huggingFaceRepo = "bartowski/Mistral-Small-4-GGUF",
            huggingFaceFile = "Mistral-Small-4-Q4_K_M.gguf"
        )
    )

    private val byModelId: Map<ModelId, ModelDescriptor> = models.associateBy { it.modelId }
    private val byRawId: Map<String, ModelDescriptor> = models.associateBy { it.id }

    val presets = listOf(
        Preset("fast_chat", "Fast Chat", "⚡", "Snappy responses, great battery life",
            ModelId.LLAMA32_1B, InferenceBackend.NPU, contextLength = 4096),
        Preset("deep_thinking", "Deep Thinking", "🧠", "Extended reasoning with thinking",
            ModelId.PHI4_MINI, InferenceBackend.GPU, contextLength = 8192, reasoningEffort = ReasoningEffort.HIGH),
        Preset("code_helper", "Code Helper", "💻", "Code generation + execution",
            ModelId.QWEN25_7B, InferenceBackend.GPU, temperature = 0.3f, contextLength = 16384),
        Preset("vision", "Vision", "📷", "Camera + image understanding",
            ModelId.GEMMA4_E4B, InferenceBackend.NPU, contextLength = 8192),
        Preset("battery_saver", "Battery Saver", "🔋", "Minimal power draw",
            ModelId.LLAMA32_1B, InferenceBackend.NPU, contextLength = 2048, batterySaver = true, maxTokSec = 30),
        Preset("creative", "Creative Writing", "✍️", "High temperature, large context",
            ModelId.QWEN25_7B, InferenceBackend.GPU, temperature = 1.2f, topP = 0.95f, contextLength = 32768)
    )

    operator fun get(id: ModelId): ModelDescriptor = byModelId.getValue(id)
    fun getOrNull(id: ModelId): ModelDescriptor? = byModelId[id]
    fun getByRawId(raw: String): ModelDescriptor? = byRawId[raw]
    fun getRecommended(): List<ModelDescriptor> = models.filter { it.recommendationTag != null }
    fun fitsInRam(model: ModelDescriptor, availableMb: Int): Boolean = model.ramRequiredMb <= availableMb
}
