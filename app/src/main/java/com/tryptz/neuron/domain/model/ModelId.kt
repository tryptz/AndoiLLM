package com.tryptz.neuron.domain.model

/**
 * Compile-time–checked model identifiers.
 *
 * T3-style: the enum IS the source of truth. Presets, registry entries,
 * and settings all reference this type — not raw strings. Renaming an
 * ID is a refactor-safe rename across the entire codebase.
 */
enum class ModelId(val raw: String) {
    GEMMA4_E2B("gemma4-e2b-int4"),
    GEMMA4_E4B("gemma4-e4b-int4"),
    LLAMA32_3B("llama32-3b-int4"),
    LLAMA32_1B("llama32-1b-int4"),
    QWEN25_7B("qwen25-7b-q4km"),
    PHI4_MINI("phi4-3b-int4"),
    MISTRAL_SMALL4("mistral-small4-int4"),
    LOCAL("local");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): ModelId? = byRaw[raw]
    }
}
