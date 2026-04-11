package com.tryptz.neuron.data.model

import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.ModelId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRegistryTest {

    @Test
    fun `all ModelId entries have a registry descriptor`() {
        for (id in ModelId.entries) {
            assertNotNull(ModelRegistry.getOrNull(id), "Missing registry entry for $id")
        }
    }

    @Test
    fun `operator get returns descriptor for valid ID`() {
        val descriptor = ModelRegistry[ModelId.LLAMA32_3B]
        assertEquals("Llama 3.2 3B", descriptor.name)
        assertEquals("llama3", descriptor.family)
    }

    @Test
    fun `getByRawId works for known IDs`() {
        val descriptor = ModelRegistry.getByRawId("qwen25-7b-q4km")
        assertNotNull(descriptor)
        assertEquals(ModelId.QWEN25_7B, descriptor.modelId)
    }

    @Test
    fun `getByRawId returns null for unknown`() {
        assertNull(ModelRegistry.getByRawId("nonexistent"))
    }

    @Test
    fun `getRecommended returns only tagged models`() {
        val recommended = ModelRegistry.getRecommended()
        assertTrue(recommended.isNotEmpty())
        assertTrue(recommended.all { it.recommendationTag != null })
    }

    @Test
    fun `fitsInRam returns true when enough RAM`() {
        val model = ModelRegistry[ModelId.LLAMA32_1B]
        assertTrue(ModelRegistry.fitsInRam(model, 8000))
    }

    @Test
    fun `fitsInRam returns false when not enough RAM`() {
        val model = ModelRegistry[ModelId.QWEN25_7B]
        assertTrue(!ModelRegistry.fitsInRam(model, 1000))
    }

    @Test
    fun `all models have at least one supported backend`() {
        for (model in ModelRegistry.models) {
            assertTrue(model.supportedBackends.isNotEmpty(), "${model.name} has no backends")
        }
    }

    @Test
    fun `all models have valid hugging face repo info`() {
        for (model in ModelRegistry.models) {
            assertTrue(model.huggingFaceRepo.isNotBlank(), "${model.name} missing repo")
            assertTrue(model.huggingFaceFile.isNotBlank(), "${model.name} missing file")
            assertTrue(model.huggingFaceFile.endsWith(".gguf"), "${model.name} file not .gguf")
        }
    }

    @Test
    fun `presets reference valid model IDs`() {
        for (preset in ModelRegistry.presets) {
            preset.modelId?.let { id ->
                assertNotNull(ModelRegistry.getOrNull(id), "Preset '${preset.name}' references invalid model $id")
            }
        }
    }

    @Test
    fun `model id accessor matches raw string`() {
        val model = ModelRegistry[ModelId.PHI4_MINI]
        assertEquals("phi4-3b-int4", model.id)
        assertEquals(model.modelId.raw, model.id)
    }

    @Test
    fun `localId overrides id accessor`() {
        val model = ModelRegistry[ModelId.LLAMA32_3B]
        val localModel = model.copy(modelId = ModelId.LOCAL, localId = "custom-uuid-123")
        assertEquals("custom-uuid-123", localModel.id)
    }

    @Test
    fun `localId null falls back to modelId raw`() {
        val model = ModelRegistry[ModelId.LLAMA32_3B]
        assertNull(model.localId)
        assertEquals(model.modelId.raw, model.id)
    }

    @Test
    fun `no duplicate model IDs in registry`() {
        val ids = ModelRegistry.models.map { it.modelId }
        assertEquals(ids.size, ids.toSet().size, "Duplicate model IDs in registry")
    }
}
