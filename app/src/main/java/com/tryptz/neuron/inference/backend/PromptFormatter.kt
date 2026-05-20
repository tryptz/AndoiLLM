package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.*

/**
 * Formats chat messages into model-specific prompt strings.
 *
 * Uses a map-driven dispatch (T3-style composition) rather than a
 * when-chain. Adding a new template means adding one map entry.
 */
object PromptFormatter {

    private val formatters: Map<ChatTemplate, (List<ChatMessage>, InferenceSettings) -> String> = mapOf(
        ChatTemplate.CHATML to ::formatChatML,
        ChatTemplate.LLAMA3 to ::formatLlama3,
        ChatTemplate.GEMMA to ::formatGemma,
        ChatTemplate.PHI to ::formatPhi,
        ChatTemplate.MISTRAL to ::formatMistral
    )

    fun format(
        messages: List<ChatMessage>,
        descriptor: ModelDescriptor,
        settings: InferenceSettings
    ): String {
        val formatter = formatters[descriptor.chatTemplate] ?: ::formatGeneric
        // Apply reasoning-effort hint for reasoning-capable models. Qwen3 and
        // DeepSeek-R1-distill recognize `/no_think` to suppress the <think> block;
        // higher levels just let the model think normally. This is the cheapest
        // wiring of settings.reasoningEffort that's actually model-visible.
        val effectiveMessages = if (
            descriptor.capabilities.reasoning &&
            messages.isNotEmpty() &&
            messages.last().role == MessageRole.USER
        ) {
            val suffix = when (settings.reasoningEffort) {
                ReasoningEffort.NONE -> " /no_think"
                ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH -> ""
            }
            if (suffix.isEmpty()) messages
            else messages.dropLast(1) + messages.last().copy(content = messages.last().content + suffix)
        } else messages
        return formatter(effectiveMessages, settings)
    }

    private fun formatChatML(messages: List<ChatMessage>, settings: InferenceSettings) = buildString {
        if (settings.systemPrompt.isNotBlank()) {
            append("<|im_start|>system\n${settings.systemPrompt}<|im_end|>\n")
        }
        for (msg in messages) {
            append("<|im_start|>${msg.role.chatTag}\n${msg.content}<|im_end|>\n")
        }
        append("<|im_start|>assistant\n")
    }

    private fun formatLlama3(messages: List<ChatMessage>, settings: InferenceSettings) = buildString {
        append("<|begin_of_text|>")
        if (settings.systemPrompt.isNotBlank()) {
            append("<|start_header_id|>system<|end_header_id|>\n\n${settings.systemPrompt}<|eot_id|>")
        }
        for (msg in messages) {
            append("<|start_header_id|>${msg.role.chatTag}<|end_header_id|>\n\n${msg.content}<|eot_id|>")
        }
        append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    private fun formatGemma(messages: List<ChatMessage>, settings: InferenceSettings) = buildString {
        if (settings.systemPrompt.isNotBlank()) {
            append("<start_of_turn>user\n[System: ${settings.systemPrompt}]\n<end_of_turn>\n")
        }
        for (msg in messages) {
            val role = if (msg.role == MessageRole.USER) "user" else "model"
            append("<start_of_turn>$role\n${msg.content}<end_of_turn>\n")
        }
        append("<start_of_turn>model\n")
    }

    private fun formatPhi(messages: List<ChatMessage>, settings: InferenceSettings) = buildString {
        if (settings.systemPrompt.isNotBlank()) {
            append("<|system|>\n${settings.systemPrompt}<|end|>\n")
        }
        for (msg in messages) {
            append("<|${msg.role.chatTag}|>\n${msg.content}<|end|>\n")
        }
        append("<|assistant|>\n")
    }

    private fun formatMistral(messages: List<ChatMessage>, settings: InferenceSettings) = buildString {
        append("<s>")
        if (settings.systemPrompt.isNotBlank()) append("[INST] ${settings.systemPrompt}\n\n")
        for ((i, msg) in messages.withIndex()) {
            if (msg.role == MessageRole.USER) {
                if (i == 0 && settings.systemPrompt.isBlank()) append("[INST] ")
                else if (i > 0 && messages[i - 1].role != MessageRole.USER) append("[INST] ")
                append("${msg.content} [/INST]")
            } else if (msg.role == MessageRole.ASSISTANT) {
                append(msg.content).append("</s>")
            }
        }
    }

    private fun formatGeneric(messages: List<ChatMessage>, settings: InferenceSettings) = buildString {
        for (msg in messages) append("${msg.role.name}: ${msg.content}\n")
        append("ASSISTANT: ")
    }

    private val MessageRole.chatTag: String
        get() = when (this) {
            MessageRole.SYSTEM -> "system"
            MessageRole.USER -> "user"
            MessageRole.ASSISTANT -> "assistant"
        }
}
