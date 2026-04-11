package com.tryptz.neuron.domain.model

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ModelIdTest {

    @Test
    fun `fromRaw returns correct enum for all known IDs`() {
        assertEquals(ModelId.GEMMA4_E2B, ModelId.fromRaw("gemma4-e2b-int4"))
        assertEquals(ModelId.GEMMA4_E4B, ModelId.fromRaw("gemma4-e4b-int4"))
        assertEquals(ModelId.LLAMA32_3B, ModelId.fromRaw("llama32-3b-int4"))
        assertEquals(ModelId.LLAMA32_1B, ModelId.fromRaw("llama32-1b-int4"))
        assertEquals(ModelId.QWEN25_7B, ModelId.fromRaw("qwen25-7b-q4km"))
        assertEquals(ModelId.PHI4_MINI, ModelId.fromRaw("phi4-3b-int4"))
        assertEquals(ModelId.MISTRAL_SMALL4, ModelId.fromRaw("mistral-small4-int4"))
        assertEquals(ModelId.LOCAL, ModelId.fromRaw("local"))
    }

    @Test
    fun `fromRaw returns null for unknown ID`() {
        assertNull(ModelId.fromRaw("unknown-model"))
        assertNull(ModelId.fromRaw(""))
    }

    @Test
    fun `raw property round-trips correctly`() {
        for (id in ModelId.entries) {
            assertEquals(id, ModelId.fromRaw(id.raw))
        }
    }

    @Test
    fun `all entries have unique raw strings`() {
        val raws = ModelId.entries.map { it.raw }
        assertEquals(raws.size, raws.toSet().size, "Duplicate raw IDs found")
    }
}
