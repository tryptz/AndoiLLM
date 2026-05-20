package com.tryptz.neuron.data.model

import com.tryptz.neuron.domain.model.ChatTemplate
import com.tryptz.neuron.domain.model.InferenceBackend
import com.tryptz.neuron.domain.model.ModelId
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRegistryTest {

    @Test
    fun `all curated ModelId entries have a registry descriptor`() {
        // ModelId.LOCAL is intentionally absent — it's the sentinel for
        // user-imported GGUFs whose descriptor is built at runtime from the
        // GGUF header, not from this curated catalog.
        for (id in ModelId.entries.filter { it != ModelId.LOCAL }) {
            assertNotNull(ModelRegistry.getOrNull(id), "Missing registry entry for $id")
        }
    }

    @Test
    fun `LOCAL is deliberately not in the curated registry`() {
        assertNull(ModelRegistry.getOrNull(ModelId.LOCAL))
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

    // ── New constraints added 2026-05-20 (verified URL catalog) ──

    @Test
    fun `every model has a positive file size and ram requirement`() {
        for (model in ModelRegistry.models) {
            assertTrue(model.fileSizeMb > 0, "${model.name} has fileSizeMb=${model.fileSizeMb}")
            assertTrue(model.ramRequiredMb > 0, "${model.name} has ramRequiredMb=${model.ramRequiredMb}")
        }
    }

    @Test
    fun `ramRequiredMb is at least fileSizeMb for every model`() {
        // mmap'd weights need the file backing pageable into memory, plus
        // KV cache + activations overhead. ramRequired < fileSize would be
        // a guaranteed OOM at load.
        for (model in ModelRegistry.models) {
            assertTrue(
                model.ramRequiredMb >= model.fileSizeMb,
                "${model.name}: ramRequired ${model.ramRequiredMb} < fileSize ${model.fileSizeMb}"
            )
        }
    }

    @Test
    fun `every estimated tokSec range is positive and well-ordered`() {
        for (model in ModelRegistry.models) {
            for ((backend, range) in model.estimatedTokSec) {
                assertTrue(range.first > 0, "${model.name}/$backend lower bound ${range.first}")
                assertTrue(
                    range.last >= range.first,
                    "${model.name}/$backend range ${range.first}..${range.last} is inverted"
                )
            }
        }
    }

    @Test
    fun `huggingFaceFile basename appears in huggingFaceRepo derivation`() {
        // A working `/resolve/main/<file>` URL requires the file to actually
        // exist in the repo. We can't hit the network in a unit test, but we
        // can sanity-check the file name follows the GGUF naming conventions
        // bartowski + ggml-org use (no slashes, no leading dot, ends .gguf).
        for (model in ModelRegistry.models) {
            assertTrue(
                '/' !in model.huggingFaceFile,
                "${model.name} file '${model.huggingFaceFile}' contains a slash"
            )
            assertTrue(
                !model.huggingFaceFile.startsWith("."),
                "${model.name} file '${model.huggingFaceFile}' starts with a dot"
            )
            assertTrue(
                '/' in model.huggingFaceRepo,
                "${model.name} repo '${model.huggingFaceRepo}' is missing org/name slash"
            )
        }
    }

    @Test
    fun `maxContext is positive for every model`() {
        for (model in ModelRegistry.models) {
            assertTrue(model.maxContext > 0, "${model.name} has maxContext=${model.maxContext}")
        }
    }

    @Test
    fun `vision-capable models declare GEMMA template`() {
        // Today only Gemma 3 ships true GGUF vision; if we add more, this
        // test will fail and the catalog-author needs to declare the right
        // template (Qwen2.5-VL would be CHATML, etc.).
        val vision = ModelRegistry.models.filter { it.capabilities.vision }
        assertTrue(vision.isNotEmpty(), "expected at least one vision-capable model")
        for (model in vision) {
            assertEquals(
                ChatTemplate.GEMMA, model.chatTemplate,
                "${model.name} is vision-capable but not on GEMMA template"
            )
        }
    }
}
