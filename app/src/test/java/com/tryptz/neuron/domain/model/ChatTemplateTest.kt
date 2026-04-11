package com.tryptz.neuron.domain.model

import org.junit.Test
import kotlin.test.assertEquals

class ChatTemplateTest {

    @Test
    fun `fromRaw returns correct template`() {
        assertEquals(ChatTemplate.CHATML, ChatTemplate.fromRaw("chatml"))
        assertEquals(ChatTemplate.LLAMA3, ChatTemplate.fromRaw("llama3"))
        assertEquals(ChatTemplate.GEMMA, ChatTemplate.fromRaw("gemma"))
        assertEquals(ChatTemplate.PHI, ChatTemplate.fromRaw("phi"))
        assertEquals(ChatTemplate.MISTRAL, ChatTemplate.fromRaw("mistral"))
    }

    @Test
    fun `fromRaw defaults to CHATML for unknown`() {
        assertEquals(ChatTemplate.CHATML, ChatTemplate.fromRaw("unknown"))
        assertEquals(ChatTemplate.CHATML, ChatTemplate.fromRaw(""))
    }

    @Test
    fun `raw property round-trips`() {
        for (template in ChatTemplate.entries) {
            assertEquals(template, ChatTemplate.fromRaw(template.raw))
        }
    }
}
