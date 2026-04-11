package com.tryptz.neuron.domain.model

/**
 * Supported chat prompt templates.
 * Each variant maps to a formatting strategy in PromptFormatter.
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
