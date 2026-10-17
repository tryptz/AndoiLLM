package com.tryptz.neuron.domain.model

/**
 * Supported chat prompt templates.
 * Each variant maps to a formatter in the Rust core (`neuron-core::template`);
 * `raw` matches the core's template ids.
 */
enum class ChatTemplate(val raw: String) {
    CHATML("chatml"),
    LLAMA3("llama3"),
    GEMMA("gemma"),
    PHI("phi"),
    MISTRAL("mistral");

    companion object {
        private val byRaw = entries.associateBy { it.raw }
        fun fromRaw(raw: String): ChatTemplate = byRaw[raw] ?: CHATML
    }
}
