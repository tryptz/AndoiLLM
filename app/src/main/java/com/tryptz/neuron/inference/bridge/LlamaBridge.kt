package com.tryptz.neuron.inference.bridge

import timber.log.Timber

/**
 * JNI bridge to the native llama.cpp library.
 * All methods marshal data between Kotlin and the C++ inference engine.
 */
object LlamaBridge {
    private var loaded = false

    fun ensureLoaded() {
        if (!loaded) {
            try {
                System.loadLibrary("neuron_inference")
                loaded = true
                Timber.d("Native inference library loaded")
            } catch (e: UnsatisfiedLinkError) {
                Timber.e(e, "Failed to load native inference library")
                throw e
            }
        }
    }

    // ── Model lifecycle ──
    external fun loadModel(
        modelPath: String,
        contextLength: Int,
        batchSize: Int,
        threadCount: Int,
        backendType: Int, // 0=NPU, 1=GPU, 2=CPU (matches InferenceBackend ordinal)
        kvCacheTypeQuant: Int // 0=F16, 1=Q8_0, 2=Q4_0
    ): Long // returns model handle, 0 on failure

    external fun unloadModel(handle: Long)
    external fun isModelLoaded(handle: Long): Boolean

    // ── Inference ──
    external fun startCompletion(
        handle: Long,
        prompt: String,
        temperature: Float,
        topP: Float,
        topK: Int,
        minP: Float,
        repeatPenalty: Float,
        maxTokens: Int
    ): Long // returns completion handle

    external fun getNextToken(completionHandle: Long): String? // null = EOS
    external fun cancelCompletion(completionHandle: Long)

    // ── Tokenizer ──
    external fun tokenize(handle: Long, text: String): IntArray
    external fun detokenize(handle: Long, tokens: IntArray): String
    external fun getContextUsed(handle: Long): Int

    // ── Telemetry ──
    external fun getLastTokensPerSec(): Float
    external fun getModelRamUsageMb(handle: Long): Int

    // ── System ──
    external fun getCpuTemperature(): Float
    external fun getAvailableRamMb(): Int
    external fun benchmarkDevice(): String // returns JSON with per-backend benchmarks
}
