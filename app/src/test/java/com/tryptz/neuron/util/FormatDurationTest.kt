package com.tryptz.neuron.util

import org.junit.Test
import kotlin.test.assertEquals

class FormatDurationTest {

    @Test
    fun `negative returns calculating`() {
        assertEquals("calculating...", (-1L).formatDuration())
        assertEquals("calculating...", (-100L).formatDuration())
    }

    @Test
    fun `zero seconds`() {
        assertEquals("0s", 0L.formatDuration())
    }

    @Test
    fun `seconds only`() {
        assertEquals("45s", 45L.formatDuration())
        assertEquals("1s", 1L.formatDuration())
        assertEquals("59s", 59L.formatDuration())
    }

    @Test
    fun `minutes and seconds`() {
        assertEquals("1m 0s", 60L.formatDuration())
        assertEquals("2m 30s", 150L.formatDuration())
        assertEquals("59m 59s", 3599L.formatDuration())
    }

    @Test
    fun `hours and minutes`() {
        assertEquals("1h 0m", 3600L.formatDuration())
        assertEquals("2h 30m", 9000L.formatDuration())
        assertEquals("24h 0m", 86400L.formatDuration())
    }
}
