package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.*
import org.junit.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class PromptFormatterTest {

    private val defaultSettings = InferenceSettings()
    private val settingsWithSystem = defaultSettings.copy(systemPrompt = "You are helpful.")

    private val userMsg = ChatMessage(
        id = "1", conversationId = "c1", role = MessageRole.USER, content = "Hello"
    )
    private val assistantMsg = ChatMessage(
        id = "2", conversationId = "c1", role = MessageRole.ASSISTANT, content = "Hi there!"
    )

    private fun descriptorFor(template: ChatTemplate) = ModelDescriptor(
        modelId = ModelId.LLAMA32_3B,
        name = "Test",
        family = "test",
        totalParams = "3B",
        quantization = Quantization.INT4,
        fileSizeMb = 1000,
        ramRequiredMb = 1500,
        maxContext = 4096,
        supportedBackends = listOf(InferenceBackend.CPU),
        chatTemplate = template,
        huggingFaceRepo = "test/repo",
        huggingFaceFile = "test.gguf"
    )

    // --- ChatML ---

    @Test
    fun `ChatML format includes user message`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.CHATML), defaultSettings)
        assertContains(result, "<|im_start|>user\nHello<|im_end|>")
    }

    @Test
    fun `ChatML format includes system prompt`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.CHATML), settingsWithSystem)
        assertContains(result, "<|im_start|>system\nYou are helpful.<|im_end|>")
    }

    @Test
    fun `ChatML format ends with assistant start`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.CHATML), defaultSettings)
        assertTrue(result.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun `ChatML omits system when blank`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.CHATML), defaultSettings)
        assertTrue("<|im_start|>system" !in result)
    }

    // --- Llama3 ---

    @Test
    fun `Llama3 format includes begin of text`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.LLAMA3), defaultSettings)
        assertTrue(result.startsWith("<|begin_of_text|>"))
    }

    @Test
    fun `Llama3 format includes user header`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.LLAMA3), defaultSettings)
        assertContains(result, "<|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|>")
    }

    @Test
    fun `Llama3 format includes system prompt`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.LLAMA3), settingsWithSystem)
        assertContains(result, "<|start_header_id|>system<|end_header_id|>\n\nYou are helpful.<|eot_id|>")
    }

    @Test
    fun `Llama3 format ends with assistant header`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.LLAMA3), defaultSettings)
        assertTrue(result.endsWith("<|start_header_id|>assistant<|end_header_id|>\n\n"))
    }

    // --- Gemma ---

    @Test
    fun `Gemma format uses start_of_turn and end_of_turn`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.GEMMA), defaultSettings)
        assertContains(result, "<start_of_turn>user\nHello<end_of_turn>")
    }

    @Test
    fun `Gemma format maps assistant to model`() {
        val result = PromptFormatter.format(
            listOf(userMsg, assistantMsg),
            descriptorFor(ChatTemplate.GEMMA),
            defaultSettings
        )
        assertContains(result, "<start_of_turn>model\nHi there!<end_of_turn>")
    }

    @Test
    fun `Gemma format ends with model turn`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.GEMMA), defaultSettings)
        assertTrue(result.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun `Gemma format wraps system prompt in user turn`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.GEMMA), settingsWithSystem)
        assertContains(result, "<start_of_turn>user\n[System: You are helpful.]\n<end_of_turn>")
    }

    // --- Phi ---

    @Test
    fun `Phi format uses pipe-delimited tags`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.PHI), defaultSettings)
        assertContains(result, "<|user|>\nHello<|end|>")
    }

    @Test
    fun `Phi format includes system`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.PHI), settingsWithSystem)
        assertContains(result, "<|system|>\nYou are helpful.<|end|>")
    }

    @Test
    fun `Phi format ends with assistant tag`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.PHI), defaultSettings)
        assertTrue(result.endsWith("<|assistant|>\n"))
    }

    // --- Mistral ---

    @Test
    fun `Mistral format wraps user in INST tags`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.MISTRAL), defaultSettings)
        assertContains(result, "[INST] Hello [/INST]")
    }

    @Test
    fun `Mistral format starts with s tag`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.MISTRAL), defaultSettings)
        assertTrue(result.startsWith("<s>"))
    }

    @Test
    fun `Mistral format includes system prompt in first INST`() {
        val result = PromptFormatter.format(listOf(userMsg), descriptorFor(ChatTemplate.MISTRAL), settingsWithSystem)
        assertContains(result, "[INST] You are helpful.")
    }

    // --- Multi-turn ---

    @Test
    fun `multi-turn ChatML conversation`() {
        val messages = listOf(userMsg, assistantMsg)
        val result = PromptFormatter.format(messages, descriptorFor(ChatTemplate.CHATML), defaultSettings)
        assertContains(result, "<|im_start|>user\nHello<|im_end|>")
        assertContains(result, "<|im_start|>assistant\nHi there!<|im_end|>")
        assertTrue(result.endsWith("<|im_start|>assistant\n"))
    }
}
