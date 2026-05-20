package com.tryptz.neuron.data.model

import com.tryptz.neuron.domain.model.*

/**
 * Curated catalog of models known to run on Snapdragon 8 Elite Gen 5
 * (OnePlus 15: 16 GB physical RAM + 12 GB virtual = ~28 GB effective).
 *
 * Every entry uses [ModelId] — presets, settings, and UI all reference
 * the enum, so renaming an ID is a compile-time–safe refactor.
 *
 * URLs are HuggingFace `/resolve/main/<file>` paths, verified 200 OK
 * as of 2026-05-19. Bartowski's repos use the `<Org>_<Model>` filename
 * prefix convention for the post-Q3-2025 uploads.
 */
object ModelRegistry {

    val models: List<ModelDescriptor> = listOf(
        // —— Vision (Gemma 3 4B IT, replaces fake "Gemma 4 E2B") ——
        ModelDescriptor(
            modelId = ModelId.GEMMA4_E2B,
            name = "Gemma 3 4B IT", family = "gemma3",
            totalParams = "4B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 2500, ramRequiredMb = 3500,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(vision = true, structuredOutput = true),
            chatTemplate = ChatTemplate.GEMMA,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 25..45, InferenceBackend.CPU to 12..22),
            huggingFaceRepo = "ggml-org/gemma-3-4b-it-GGUF",
            huggingFaceFile = "gemma-3-4b-it-Q4_K_M.gguf",
            recommendationTag = "Best for Vision"
        ),

        // —— Function calling (Hermes 3, replaces fake "Gemma 4 E4B") ——
        ModelDescriptor(
            modelId = ModelId.GEMMA4_E4B,
            name = "Hermes 3 (8B)", family = "llama3",
            totalParams = "8.0B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 4920, ramRequiredMb = 5500,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(functionCalling = true, structuredOutput = true),
            chatTemplate = ChatTemplate.LLAMA3,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 18..32, InferenceBackend.CPU to 7..13),
            huggingFaceRepo = "bartowski/Hermes-3-Llama-3.1-8B-GGUF",
            huggingFaceFile = "Hermes-3-Llama-3.1-8B-Q4_K_M.gguf",
            recommendationTag = "Best for Tools"
        ),

        // —— Llama 3.2 3B (kept, URL re-verified) ——
        ModelDescriptor(
            modelId = ModelId.LLAMA32_3B,
            name = "Llama 3.2 3B", family = "llama3",
            totalParams = "3.2B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 2020, ramRequiredMb = 2500,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            chatTemplate = ChatTemplate.LLAMA3,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 30..50, InferenceBackend.CPU to 18..30),
            huggingFaceRepo = "bartowski/Llama-3.2-3B-Instruct-GGUF",
            huggingFaceFile = "Llama-3.2-3B-Instruct-Q4_K_M.gguf"
        ),

        // —— Llama 3.2 1B (the "tiny but useful" entry) ——
        ModelDescriptor(
            modelId = ModelId.LLAMA32_1B,
            name = "Llama 3.2 1B", family = "llama3",
            totalParams = "1.2B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 770, ramRequiredMb = 1100,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            chatTemplate = ChatTemplate.LLAMA3,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 55..85, InferenceBackend.CPU to 35..55),
            huggingFaceRepo = "bartowski/Llama-3.2-1B-Instruct-GGUF",
            huggingFaceFile = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            recommendationTag = "Best for Speed"
        ),

        // —— Qwen 2.5 7B (URL fixed to bartowski — official repo path didn't resolve) ——
        ModelDescriptor(
            modelId = ModelId.QWEN25_7B,
            name = "Qwen 2.5 7B Instruct", family = "qwen2",
            totalParams = "7.6B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 4700, ramRequiredMb = 5400,
            maxContext = 32768,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(structuredOutput = true),
            chatTemplate = ChatTemplate.CHATML,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 20..32, InferenceBackend.CPU to 8..14),
            huggingFaceRepo = "bartowski/Qwen2.5-7B-Instruct-GGUF",
            huggingFaceFile = "Qwen2.5-7B-Instruct-Q4_K_M.gguf"
        ),

        // —— Phi-4 mini (URL fixed — bartowski's new `microsoft_` prefix) ——
        ModelDescriptor(
            modelId = ModelId.PHI4_MINI,
            name = "Phi-4 mini", family = "phi",
            totalParams = "3.8B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 2480, ramRequiredMb = 3000,
            maxContext = 131072,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(structuredOutput = true),
            chatTemplate = ChatTemplate.PHI,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 28..45, InferenceBackend.CPU to 16..28),
            huggingFaceRepo = "bartowski/microsoft_Phi-4-mini-instruct-GGUF",
            huggingFaceFile = "microsoft_Phi-4-mini-instruct-Q4_K_M.gguf"
        ),

        // —— Qwen 3 8B (the research's top all-rounder, replaces fake "Mistral Small 4") ——
        ModelDescriptor(
            modelId = ModelId.MISTRAL_SMALL4,
            name = "Qwen 3 8B", family = "qwen3",
            totalParams = "8.2B",
            quantization = Quantization.Q4_K_M, fileSizeMb = 4920, ramRequiredMb = 5600,
            maxContext = 32768,
            supportedBackends = listOf(InferenceBackend.GPU, InferenceBackend.CPU),
            capabilities = ModelCapabilities(reasoning = true, functionCalling = true, structuredOutput = true),
            chatTemplate = ChatTemplate.CHATML,
            estimatedTokSec = mapOf(InferenceBackend.GPU to 18..30, InferenceBackend.CPU to 7..13),
            huggingFaceRepo = "bartowski/Qwen_Qwen3-8B-GGUF",
            huggingFaceFile = "Qwen_Qwen3-8B-Q4_K_M.gguf",
            recommendationTag = "Best for Quality"
        )
    )

    private val byModelId: Map<ModelId, ModelDescriptor> = models.associateBy { it.modelId }
    private val byRawId: Map<String, ModelDescriptor> = models.associateBy { it.id }

    val presets = listOf(
        Preset("fast_chat", "Fast Chat", "⚡", "Snappy responses, great battery life",
            ModelId.LLAMA32_1B, InferenceBackend.GPU, contextLength = 4096),
        Preset("deep_thinking", "Deep Thinking", "🧠", "Extended reasoning with thinking",
            ModelId.MISTRAL_SMALL4, InferenceBackend.GPU, contextLength = 8192, reasoningEffort = ReasoningEffort.HIGH),
        Preset("code_helper", "Code Helper", "💻", "Code generation + execution",
            ModelId.QWEN25_7B, InferenceBackend.GPU, temperature = 0.3f, contextLength = 16384),
        Preset("vision", "Vision", "📷", "Camera + image understanding",
            ModelId.GEMMA4_E2B, InferenceBackend.GPU, contextLength = 8192),
        Preset("battery_saver", "Battery Saver", "🔋", "Minimal power draw",
            ModelId.LLAMA32_1B, InferenceBackend.CPU, contextLength = 2048, batterySaver = true, maxTokSec = 30),
        Preset("creative", "Creative Writing", "✍️", "High temperature, large context",
            ModelId.QWEN25_7B, InferenceBackend.GPU, temperature = 1.2f, topP = 0.95f, contextLength = 32768)
    )

    operator fun get(id: ModelId): ModelDescriptor = byModelId.getValue(id)
    fun getOrNull(id: ModelId): ModelDescriptor? = byModelId[id]
    fun getByRawId(raw: String): ModelDescriptor? = byRawId[raw]
    fun getRecommended(): List<ModelDescriptor> = models.filter { it.recommendationTag != null }
    fun fitsInRam(model: ModelDescriptor, availableMb: Int): Boolean = model.ramRequiredMb <= availableMb
}
