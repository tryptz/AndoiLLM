package com.tryptz.neuron.util

import org.junit.Test
import kotlin.test.assertEquals

class FormatMbAsGbTest {

    @Test
    fun `below 1 GB shows MB`() {
        assertEquals("512 MB", 512.formatMbAsGb())
        assertEquals("1 MB", 1.formatMbAsGb())
        assertEquals("1023 MB", 1023.formatMbAsGb())
    }

    @Test
    fun `exactly 1 GB`() {
        assertEquals("1.0 GB", 1024.formatMbAsGb())
    }

    @Test
    fun `fractional GB`() {
        assertEquals("1.5 GB", 1536.formatMbAsGb())
        assertEquals("2.0 GB", 2048.formatMbAsGb())
        assertEquals("4.5 GB", 4608.formatMbAsGb())
    }

    @Test
    fun `zero MB`() {
        assertEquals("0 MB", 0.formatMbAsGb())
    }
}
