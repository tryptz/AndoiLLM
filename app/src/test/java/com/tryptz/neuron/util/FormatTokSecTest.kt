package com.tryptz.neuron.util

import org.junit.Test
import kotlin.test.assertEquals

class FormatTokSecTest {

    @Test
    fun `formats with one decimal place`() {
        assertEquals("25.0 tok/s", 25.0f.formatTokSec())
        assertEquals("0.0 tok/s", 0.0f.formatTokSec())
        assertEquals("150.5 tok/s", 150.5f.formatTokSec())
    }

    @Test
    fun `rounds to one decimal`() {
        assertEquals("33.3 tok/s", 33.33f.formatTokSec())
        assertEquals("99.9 tok/s", 99.89f.formatTokSec())
    }
}
