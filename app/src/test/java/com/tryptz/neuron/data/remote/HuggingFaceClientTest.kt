package com.tryptz.neuron.data.remote

import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuggingFaceClientTest {

    @Test
    fun `plain gguf files are standalone models`() {
        assertTrue(isStandaloneGgufModel("Llama-3.2-3B-Instruct-Q4_K_M.gguf"))
        assertTrue(isStandaloneGgufModel("gemma-3-4b-it-Q8_0.gguf"))
    }

    @Test
    fun `nested gguf files are standalone models`() {
        assertTrue(isStandaloneGgufModel("Q4_K_M/Qwen3-8B-Q4_K_M.gguf"))
    }

    @Test
    fun `non-gguf files are rejected`() {
        assertFalse(isStandaloneGgufModel("README.md"))
        assertFalse(isStandaloneGgufModel("config.json"))
    }

    @Test
    fun `mmproj vision projectors are rejected`() {
        assertFalse(isStandaloneGgufModel("mmproj-model-f16.gguf"))
        assertFalse(isStandaloneGgufModel("gemma-3-4b-it-mmproj-F16.gguf"))
    }

    @Test
    fun `multi-part split shards are rejected`() {
        assertFalse(isStandaloneGgufModel("Qwen3-235B-Q4_K_M-00001-of-00005.gguf"))
        assertFalse(isStandaloneGgufModel("Q8_0/big-model-Q8_0-00002-of-00003.gguf"))
    }

    @Test
    fun `shard-like names that are not the split pattern are kept`() {
        // Only the llama.cpp -NNNNN-of-NNNNN suffix marks a split file.
        assertTrue(isStandaloneGgufModel("model-1-of-2.gguf"))
    }
}
