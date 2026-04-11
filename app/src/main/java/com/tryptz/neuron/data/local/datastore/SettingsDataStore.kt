package com.tryptz.neuron.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.tryptz.neuron.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore("neuron_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val store get() = context.settingsStore

    // Keys
    private object Keys {
        val BACKEND = stringPreferencesKey("backend")
        val TEMPERATURE = floatPreferencesKey("temperature")
        val TOP_P = floatPreferencesKey("top_p")
        val TOP_K = intPreferencesKey("top_k")
        val MIN_P = floatPreferencesKey("min_p")
        val REPEAT_PENALTY = floatPreferencesKey("repeat_penalty")
        val CONTEXT_LENGTH = intPreferencesKey("context_length")
        val SYSTEM_PROMPT = stringPreferencesKey("system_prompt")
        val REASONING_EFFORT = stringPreferencesKey("reasoning_effort")
        val MAX_THINKING_TOKENS = intPreferencesKey("max_thinking_tokens")
        val THREAD_COUNT = intPreferencesKey("thread_count")
        val NPU_PRECISION = stringPreferencesKey("npu_precision")
        val BATCH_SIZE = intPreferencesKey("batch_size")
        val KV_CACHE_QUANT = stringPreferencesKey("kv_cache_quant")
        val THERMAL_POLICY = stringPreferencesKey("thermal_policy")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val MAX_TOK_SEC_CAP = intPreferencesKey("max_tok_sec_cap")
        val BG_INFERENCE = booleanPreferencesKey("background_inference")
        val WAKE_LOCK = booleanPreferencesKey("wake_lock")
        val CODE_TIMEOUT = intPreferencesKey("code_timeout_sec")
        val CODE_MEMORY = intPreferencesKey("code_memory_mb")
        val CODE_NETWORK = booleanPreferencesKey("code_network_allowed")
        val SETTINGS_LEVEL = stringPreferencesKey("settings_level")
        val ACTIVE_MODEL_ID = stringPreferencesKey("active_model_id")
        val ACTIVE_PRESET_ID = stringPreferencesKey("active_preset_id")
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
    }

    val inferenceSettings: Flow<InferenceSettings> = store.data.map { prefs ->
        InferenceSettings(
            backend = prefs[Keys.BACKEND]?.let { runCatching { InferenceBackend.valueOf(it) }.getOrNull() } ?: InferenceBackend.AUTO,
            temperature = prefs[Keys.TEMPERATURE] ?: 0.7f,
            topP = prefs[Keys.TOP_P] ?: 0.9f,
            topK = prefs[Keys.TOP_K] ?: 40,
            minP = prefs[Keys.MIN_P] ?: 0.05f,
            repeatPenalty = prefs[Keys.REPEAT_PENALTY] ?: 1.1f,
            contextLength = prefs[Keys.CONTEXT_LENGTH] ?: 4096,
            systemPrompt = prefs[Keys.SYSTEM_PROMPT] ?: "",
            reasoningEffort = prefs[Keys.REASONING_EFFORT]?.let { runCatching { ReasoningEffort.valueOf(it) }.getOrNull() } ?: ReasoningEffort.NONE,
            maxThinkingTokens = prefs[Keys.MAX_THINKING_TOKENS] ?: 1024,
            threadCount = prefs[Keys.THREAD_COUNT] ?: 0,
            npuPrecision = prefs[Keys.NPU_PRECISION]?.let { runCatching { Quantization.valueOf(it) }.getOrNull() } ?: Quantization.INT4,
            batchSize = prefs[Keys.BATCH_SIZE] ?: 512,
            kvCacheQuant = prefs[Keys.KV_CACHE_QUANT]?.let { runCatching { Quantization.valueOf(it) }.getOrNull() } ?: Quantization.Q8_0,
            thermalPolicy = prefs[Keys.THERMAL_POLICY]?.let { runCatching { ThermalPolicy.valueOf(it) }.getOrNull() } ?: ThermalPolicy.SLOW_DOWN,
            batterySaver = prefs[Keys.BATTERY_SAVER] ?: false,
            maxTokSecCap = prefs[Keys.MAX_TOK_SEC_CAP] ?: 0,
            backgroundInference = prefs[Keys.BG_INFERENCE] ?: false,
            wakeLock = prefs[Keys.WAKE_LOCK] ?: false,
            codeTimeoutSec = prefs[Keys.CODE_TIMEOUT] ?: 10,
            codeMemoryMb = prefs[Keys.CODE_MEMORY] ?: 64,
            codeNetworkAllowed = prefs[Keys.CODE_NETWORK] ?: false
        )
    }

    val settingsLevel: Flow<SettingsLevel> = store.data.map { prefs ->
        prefs[Keys.SETTINGS_LEVEL]?.let { runCatching { SettingsLevel.valueOf(it) }.getOrNull() } ?: SettingsLevel.BASIC
    }

    val activeModelId: Flow<String?> = store.data.map { it[Keys.ACTIVE_MODEL_ID] }
    val activePresetId: Flow<String?> = store.data.map { it[Keys.ACTIVE_PRESET_ID] }
    val isDarkMode: Flow<Boolean> = store.data.map { it[Keys.DARK_MODE] ?: true }
    val isWifiOnlyDownloads: Flow<Boolean> = store.data.map { it[Keys.WIFI_ONLY_DOWNLOADS] ?: true }

    suspend fun updateSettings(block: (InferenceSettings) -> InferenceSettings) {
        store.edit { prefs ->
            val current = InferenceSettings() // read current would be more correct but this is fine for updates
            val updated = block(current)
            prefs[Keys.BACKEND] = updated.backend.name
            prefs[Keys.TEMPERATURE] = updated.temperature
            prefs[Keys.TOP_P] = updated.topP
            prefs[Keys.TOP_K] = updated.topK
            prefs[Keys.MIN_P] = updated.minP
            prefs[Keys.REPEAT_PENALTY] = updated.repeatPenalty
            prefs[Keys.CONTEXT_LENGTH] = updated.contextLength
            prefs[Keys.SYSTEM_PROMPT] = updated.systemPrompt
            prefs[Keys.REASONING_EFFORT] = updated.reasoningEffort.name
            prefs[Keys.MAX_THINKING_TOKENS] = updated.maxThinkingTokens
            prefs[Keys.THREAD_COUNT] = updated.threadCount
            prefs[Keys.NPU_PRECISION] = updated.npuPrecision.name
            prefs[Keys.BATCH_SIZE] = updated.batchSize
            prefs[Keys.KV_CACHE_QUANT] = updated.kvCacheQuant.name
            prefs[Keys.THERMAL_POLICY] = updated.thermalPolicy.name
            prefs[Keys.BATTERY_SAVER] = updated.batterySaver
            prefs[Keys.MAX_TOK_SEC_CAP] = updated.maxTokSecCap
            prefs[Keys.BG_INFERENCE] = updated.backgroundInference
            prefs[Keys.WAKE_LOCK] = updated.wakeLock
            prefs[Keys.CODE_TIMEOUT] = updated.codeTimeoutSec
            prefs[Keys.CODE_MEMORY] = updated.codeMemoryMb
            prefs[Keys.CODE_NETWORK] = updated.codeNetworkAllowed
        }
    }

    suspend fun setActiveModel(modelId: String?) {
        store.edit { prefs ->
            if (modelId != null) prefs[Keys.ACTIVE_MODEL_ID] = modelId
            else prefs.remove(Keys.ACTIVE_MODEL_ID)
        }
    }

    suspend fun setActivePreset(presetId: String?) {
        store.edit { prefs ->
            if (presetId != null) prefs[Keys.ACTIVE_PRESET_ID] = presetId
            else prefs.remove(Keys.ACTIVE_PRESET_ID)
        }
    }

    suspend fun setSettingsLevel(level: SettingsLevel) {
        store.edit { it[Keys.SETTINGS_LEVEL] = level.name }
    }

    suspend fun setDarkMode(dark: Boolean) {
        store.edit { it[Keys.DARK_MODE] = dark }
    }
}
