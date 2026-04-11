package com.tryptz.neuron.util

import com.tryptz.neuron.domain.model.*
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValidationTest {

    private val defaultSettings = InferenceSettings()

    private val testModel = ModelDescriptor(
        modelId = ModelId.LLAMA32_3B,
        name = "Test Model",
        family = "llama3",
        totalParams = "3B",
        quantization = Quantization.INT4,
        fileSizeMb = 1800,
        ramRequiredMb = 2200,
        maxContext = 8192,
        supportedBackends = listOf(InferenceBackend.NPU, InferenceBackend.GPU, InferenceBackend.CPU),
        chatTemplate = ChatTemplate.LLAMA3,
        huggingFaceRepo = "test/repo",
        huggingFaceFile = "test.gguf"
    )

    @Test
    fun `default settings pass validation`() {
        val errors = Validation.validateSettings(defaultSettings, testModel)
        assertTrue(errors.isEmpty(), "Default settings should have no errors but got: $errors")
    }

    @Test
    fun `temperature out of range`() {
        val settings = defaultSettings.copy(temperature = -0.1f)
        val errors = Validation.validateSettings(settings, null)
        assertTrue(errors.any { "Temperature" in it })

        val settings2 = defaultSettings.copy(temperature = 2.1f)
        val errors2 = Validation.validateSettings(settings2, null)
        assertTrue(errors2.any { "Temperature" in it })
    }

    @Test
    fun `temperature at bounds is valid`() {
        val low = Validation.validateSettings(defaultSettings.copy(temperature = 0f), null)
        val high = Validation.validateSettings(defaultSettings.copy(temperature = 2f), null)
        assertFalse(low.any { "Temperature" in it })
        assertFalse(high.any { "Temperature" in it })
    }

    @Test
    fun `topP out of range`() {
        val errors = Validation.validateSettings(defaultSettings.copy(topP = 1.1f), null)
        assertTrue(errors.any { "Top P" in it })

        val errors2 = Validation.validateSettings(defaultSettings.copy(topP = -0.1f), null)
        assertTrue(errors2.any { "Top P" in it })
    }

    @Test
    fun `topK out of range`() {
        val errors = Validation.validateSettings(defaultSettings.copy(topK = 0), null)
        assertTrue(errors.any { "Top K" in it })

        val errors2 = Validation.validateSettings(defaultSettings.copy(topK = 201), null)
        assertTrue(errors2.any { "Top K" in it })
    }

    @Test
    fun `context length too small`() {
        val errors = Validation.validateSettings(defaultSettings.copy(contextLength = 64), null)
        assertTrue(errors.any { "Context length" in it })
    }

    @Test
    fun `context length exceeds model max`() {
        val settings = defaultSettings.copy(contextLength = 16384)
        val errors = Validation.validateSettings(settings, testModel)
        assertTrue(errors.any { "exceeds model max" in it })
    }

    @Test
    fun `unsupported backend for model`() {
        val model = testModel.copy(supportedBackends = listOf(InferenceBackend.CPU))
        val settings = defaultSettings.copy(backend = InferenceBackend.NPU)
        val errors = Validation.validateSettings(settings, model)
        assertTrue(errors.any { "not supported" in it })
    }

    @Test
    fun `AUTO backend always valid`() {
        val model = testModel.copy(supportedBackends = listOf(InferenceBackend.CPU))
        val settings = defaultSettings.copy(backend = InferenceBackend.AUTO)
        val errors = Validation.validateSettings(settings, model)
        assertFalse(errors.any { "not supported" in it })
    }

    @Test
    fun `thread count out of range`() {
        val errors = Validation.validateSettings(defaultSettings.copy(threadCount = -1), null)
        assertTrue(errors.any { "Thread count" in it })

        val errors2 = Validation.validateSettings(defaultSettings.copy(threadCount = 9), null)
        assertTrue(errors2.any { "Thread count" in it })
    }

    @Test
    fun `batch size out of range`() {
        val errors = Validation.validateSettings(defaultSettings.copy(batchSize = 0), null)
        assertTrue(errors.any { "Batch size" in it })

        val errors2 = Validation.validateSettings(defaultSettings.copy(batchSize = 5000), null)
        assertTrue(errors2.any { "Batch size" in it })
    }

    @Test
    fun `code timeout out of range`() {
        val errors = Validation.validateSettings(defaultSettings.copy(codeTimeoutSec = 0), null)
        assertTrue(errors.any { "Code timeout" in it })

        val errors2 = Validation.validateSettings(defaultSettings.copy(codeTimeoutSec = 301), null)
        assertTrue(errors2.any { "Code timeout" in it })
    }

    @Test
    fun `code memory out of range`() {
        val errors = Validation.validateSettings(defaultSettings.copy(codeMemoryMb = 4), null)
        assertTrue(errors.any { "Code memory" in it })

        val errors2 = Validation.validateSettings(defaultSettings.copy(codeMemoryMb = 1024), null)
        assertTrue(errors2.any { "Code memory" in it })
    }

    @Test
    fun `multiple errors accumulated`() {
        val settings = defaultSettings.copy(
            temperature = 5f,
            topP = 2f,
            topK = 0,
            contextLength = 10
        )
        val errors = Validation.validateSettings(settings, null)
        assertEquals(4, errors.size)
    }

    @Test
    fun `model fits in RAM`() {
        assertTrue(Validation.modelFitsInRam(testModel, 3000))
        assertTrue(Validation.modelFitsInRam(testModel, 2200))
    }

    @Test
    fun `model does not fit in RAM`() {
        assertFalse(Validation.modelFitsInRam(testModel, 2000))
        assertFalse(Validation.modelFitsInRam(testModel, 1000))
    }
}
