package com.tryptz.neuron.data.local.datastore

import androidx.datastore.preferences.core.mutablePreferencesOf
import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.InferenceSettings
import com.tryptz.neuron.domain.model.Quantization
import com.tryptz.neuron.domain.model.ReasoningEffort
import com.tryptz.neuron.domain.model.ThermalPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for the `Preferences.toInferenceSettings()` mapper extracted from
 * [SettingsDataStore].
 *
 * This is the test that locks down the `updateSettings` bug fix: `updateSettings` used to
 * build `InferenceSettings()` (all defaults) as its "current" value, so editing one field
 * wiped every other persisted field back to its default. The fix routes `updateSettings`
 * through this same `toInferenceSettings()` mapper, reading the *real* stored values first.
 * As long as this mapper round-trips a partial preference set correctly, the read-modify-write
 * in `updateSettings` preserves untouched fields.
 */
class SettingsMapperTest {

    @Test
    fun `empty preferences map to all defaults`() {
        val result = mutablePreferencesOf().toInferenceSettings()

        assertEquals(InferenceSettings(), result)
    }

    @Test
    fun `set keys are read back and unset keys keep defaults`() {
        val prefs = mutablePreferencesOf().apply {
            this[SettingsKeys.TEMPERATURE] = 0.42f
            this[SettingsKeys.TOP_K] = 17
            this[SettingsKeys.SYSTEM_PROMPT] = "you are a helpful assistant"
            this[SettingsKeys.BACKEND] = InferenceBackend.GPU.name
            this[SettingsKeys.REASONING_EFFORT] = ReasoningEffort.HIGH.name
            this[SettingsKeys.BATTERY_SAVER] = true
        }

        val result = prefs.toInferenceSettings()

        // Explicitly persisted values come through.
        assertEquals(0.42f, result.temperature, 1e-6f)
        assertEquals(17, result.topK)
        assertEquals("you are a helpful assistant", result.systemPrompt)
        assertEquals(InferenceBackend.GPU, result.backend)
        assertEquals(ReasoningEffort.HIGH, result.reasoningEffort)
        assertEquals(true, result.batterySaver)

        // Everything else falls back to the InferenceSettings defaults.
        val defaults = InferenceSettings()
        assertEquals(defaults.topP, result.topP, 1e-6f)
        assertEquals(defaults.minP, result.minP, 1e-6f)
        assertEquals(defaults.repeatPenalty, result.repeatPenalty, 1e-6f)
        assertEquals(defaults.contextLength, result.contextLength)
        assertEquals(defaults.maxThinkingTokens, result.maxThinkingTokens)
        assertEquals(defaults.threadCount, result.threadCount)
        assertEquals(defaults.npuPrecision, result.npuPrecision)
        assertEquals(defaults.batchSize, result.batchSize)
        assertEquals(defaults.kvCacheQuant, result.kvCacheQuant)
        assertEquals(defaults.thermalPolicy, result.thermalPolicy)
        assertEquals(defaults.maxTokSecCap, result.maxTokSecCap)
        assertEquals(defaults.backgroundInference, result.backgroundInference)
        assertEquals(defaults.wakeLock, result.wakeLock)
        assertEquals(defaults.codeTimeoutSec, result.codeTimeoutSec)
        assertEquals(defaults.codeMemoryMb, result.codeMemoryMb)
        assertEquals(defaults.codeNetworkAllowed, result.codeNetworkAllowed)
    }

    @Test
    fun `a fully populated preference set round-trips every field`() {
        val expected = InferenceSettings(
            backend = InferenceBackend.NPU,
            temperature = 0.33f,
            topP = 0.81f,
            topK = 64,
            minP = 0.12f,
            repeatPenalty = 1.25f,
            contextLength = 8192,
            systemPrompt = "system",
            reasoningEffort = ReasoningEffort.MEDIUM,
            maxThinkingTokens = 2048,
            threadCount = 6,
            npuPrecision = Quantization.INT8,
            batchSize = 256,
            kvCacheQuant = Quantization.Q4_K_M,
            thermalPolicy = ThermalPolicy.PAUSE,
            batterySaver = true,
            maxTokSecCap = 30,
            backgroundInference = true,
            wakeLock = true,
            codeTimeoutSec = 25,
            codeMemoryMb = 128,
            codeNetworkAllowed = true
        )

        val prefs = mutablePreferencesOf().apply {
            this[SettingsKeys.BACKEND] = expected.backend.name
            this[SettingsKeys.TEMPERATURE] = expected.temperature
            this[SettingsKeys.TOP_P] = expected.topP
            this[SettingsKeys.TOP_K] = expected.topK
            this[SettingsKeys.MIN_P] = expected.minP
            this[SettingsKeys.REPEAT_PENALTY] = expected.repeatPenalty
            this[SettingsKeys.CONTEXT_LENGTH] = expected.contextLength
            this[SettingsKeys.SYSTEM_PROMPT] = expected.systemPrompt
            this[SettingsKeys.REASONING_EFFORT] = expected.reasoningEffort.name
            this[SettingsKeys.MAX_THINKING_TOKENS] = expected.maxThinkingTokens
            this[SettingsKeys.THREAD_COUNT] = expected.threadCount
            this[SettingsKeys.NPU_PRECISION] = expected.npuPrecision.name
            this[SettingsKeys.BATCH_SIZE] = expected.batchSize
            this[SettingsKeys.KV_CACHE_QUANT] = expected.kvCacheQuant.name
            this[SettingsKeys.THERMAL_POLICY] = expected.thermalPolicy.name
            this[SettingsKeys.BATTERY_SAVER] = expected.batterySaver
            this[SettingsKeys.MAX_TOK_SEC_CAP] = expected.maxTokSecCap
            this[SettingsKeys.BG_INFERENCE] = expected.backgroundInference
            this[SettingsKeys.WAKE_LOCK] = expected.wakeLock
            this[SettingsKeys.CODE_TIMEOUT] = expected.codeTimeoutSec
            this[SettingsKeys.CODE_MEMORY] = expected.codeMemoryMb
            this[SettingsKeys.CODE_NETWORK] = expected.codeNetworkAllowed
        }

        assertEquals(expected, prefs.toInferenceSettings())
    }

    @Test
    fun `an unrecognised enum string falls back to the default rather than crashing`() {
        val prefs = mutablePreferencesOf().apply {
            this[SettingsKeys.BACKEND] = "NOT_A_REAL_BACKEND"
            this[SettingsKeys.KV_CACHE_QUANT] = "garbage"
        }

        val result = prefs.toInferenceSettings()

        assertEquals(InferenceBackend.AUTO, result.backend)
        assertEquals(Quantization.Q8_0, result.kvCacheQuant)
    }
}
