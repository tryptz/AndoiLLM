package com.tryptz.neuron.util

import com.tryptz.neuron.domain.model.ChatTemplate
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class GgufMetadataReaderTest {

    @Test
    fun `GgufMetadata inferredChatTemplate uses detected template first`() {
        val meta = GgufMetadata(
            architecture = "llama",
            chatTemplate = ChatTemplate.CHATML
        )
        assertEquals(ChatTemplate.CHATML, meta.inferredChatTemplate)
    }

    @Test
    fun `GgufMetadata inferredChatTemplate falls back to architecture mapping`() {
        val llamaMeta = GgufMetadata(architecture = "llama")
        assertEquals(ChatTemplate.LLAMA3, llamaMeta.inferredChatTemplate)

        val gemmaMeta = GgufMetadata(architecture = "gemma")
        assertEquals(ChatTemplate.GEMMA, gemmaMeta.inferredChatTemplate)

        val phiMeta = GgufMetadata(architecture = "phi")
        assertEquals(ChatTemplate.PHI, phiMeta.inferredChatTemplate)

        val qwenMeta = GgufMetadata(architecture = "qwen2")
        assertEquals(ChatTemplate.CHATML, qwenMeta.inferredChatTemplate)

        val mistralMeta = GgufMetadata(architecture = "mistral")
        assertEquals(ChatTemplate.MISTRAL, mistralMeta.inferredChatTemplate)
    }

    @Test
    fun `GgufMetadata inferredChatTemplate defaults to CHATML`() {
        val meta = GgufMetadata()
        assertEquals(ChatTemplate.CHATML, meta.inferredChatTemplate)

        val unknownArch = GgufMetadata(architecture = "unknown_arch")
        assertEquals(ChatTemplate.CHATML, unknownArch.inferredChatTemplate)
    }

    @Test
    fun `GgufMetadata displayQuantization shows label when available`() {
        val meta = GgufMetadata(quantization = com.tryptz.neuron.domain.model.Quantization.Q4_K_M)
        assertEquals("Q4_K_M", meta.displayQuantization)
    }

    @Test
    fun `GgufMetadata displayQuantization falls back to fileType`() {
        val meta = GgufMetadata(fileType = 99)
        assertEquals("type_99", meta.displayQuantization)
    }

    @Test
    fun `GgufMetadata displayQuantization shows Unknown when nothing`() {
        val meta = GgufMetadata()
        assertEquals("Unknown", meta.displayQuantization)
    }

    @Test
    fun `GgufMetadata stores all fields correctly`() {
        val meta = GgufMetadata(
            name = "Test Model",
            architecture = "llama",
            contextLength = 8192,
            fileType = 15,
            parameterCount = 7_000_000_000
        )
        assertEquals("Test Model", meta.name)
        assertEquals("llama", meta.architecture)
        assertEquals(8192, meta.contextLength)
        assertEquals(15, meta.fileType)
        assertEquals(7_000_000_000, meta.parameterCount)
    }

    @Test
    fun `GgufMetadata null fields default correctly`() {
        val meta = GgufMetadata()
        assertNull(meta.name)
        assertNull(meta.architecture)
        assertNull(meta.contextLength)
        assertNull(meta.quantization)
        assertNull(meta.fileType)
        assertNull(meta.parameterCount)
        assertNull(meta.chatTemplate)
    }

    @Test
    fun `quantLabelFromFileName detects common quant suffixes`() {
        assertEquals("Q4_K_M", GgufMetadataReader.quantLabelFromFileName("Llama-3.2-3B-Instruct-Q4_K_M.gguf"))
        assertEquals("Q8_0", GgufMetadataReader.quantLabelFromFileName("gemma-3-4b-it-q8_0.gguf"))
        assertEquals("Q6_K", GgufMetadataReader.quantLabelFromFileName("model-Q6_K.gguf"))
        assertEquals("IQ4_XS", GgufMetadataReader.quantLabelFromFileName("model-IQ4_XS.gguf"))
        assertEquals("F16", GgufMetadataReader.quantLabelFromFileName("mistral-7b-f16.gguf"))
    }

    @Test
    fun `quantLabelFromFileName returns null when no quant in name`() {
        assertNull(GgufMetadataReader.quantLabelFromFileName("my-model.gguf"))
    }
}
