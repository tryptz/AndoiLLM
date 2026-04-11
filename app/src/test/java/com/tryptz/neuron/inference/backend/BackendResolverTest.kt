package com.tryptz.neuron.inference.backend

import com.tryptz.neuron.domain.model.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BackendResolverTest {

    private fun model(
        backends: List<InferenceBackend>,
        ramRequiredMb: Int = 2000
    ) = ModelDescriptor(
        modelId = ModelId.LLAMA32_3B,
        name = "Test",
        family = "test",
        totalParams = "3B",
        quantization = Quantization.INT4,
        fileSizeMb = 1000,
        ramRequiredMb = ramRequiredMb,
        maxContext = 4096,
        supportedBackends = backends,
        chatTemplate = ChatTemplate.CHATML,
        huggingFaceRepo = "test/repo",
        huggingFaceFile = "test.gguf"
    )

    @Test
    fun `prefers NPU when available and model fits in 4GB`() {
        val result = BackendResolver.resolve(
            model(listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU), ramRequiredMb = 3000)
        )
        assertEquals(InferenceBackend.NPU, result)
    }

    @Test
    fun `falls back to GPU when NPU available but model too large`() {
        val result = BackendResolver.resolve(
            model(listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU), ramRequiredMb = 5000)
        )
        assertEquals(InferenceBackend.GPU, result)
    }

    @Test
    fun `uses GPU when NPU not available`() {
        val result = BackendResolver.resolve(
            model(listOf(InferenceBackend.GPU, InferenceBackend.CPU))
        )
        assertEquals(InferenceBackend.GPU, result)
    }

    @Test
    fun `falls back to CPU when only CPU available`() {
        val result = BackendResolver.resolve(
            model(listOf(InferenceBackend.CPU))
        )
        assertEquals(InferenceBackend.CPU, result)
    }

    @Test
    fun `NPU threshold is 4000 MB`() {
        val exactly4000 = BackendResolver.resolve(
            model(listOf(InferenceBackend.NPU, InferenceBackend.GPU), ramRequiredMb = 4000)
        )
        assertEquals(InferenceBackend.NPU, exactly4000)

        val above4000 = BackendResolver.resolve(
            model(listOf(InferenceBackend.NPU, InferenceBackend.GPU), ramRequiredMb = 4001)
        )
        assertEquals(InferenceBackend.GPU, above4000)
    }

    @Test
    fun `resolveThreadCount returns reasonable value`() {
        val threadCount = BackendResolver.resolveThreadCount()
        assertTrue(threadCount in 2..6, "Thread count $threadCount not in 2..6")
    }
}
