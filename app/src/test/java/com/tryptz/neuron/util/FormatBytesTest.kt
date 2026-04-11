package com.tryptz.neuron.util

import org.junit.Test
import kotlin.test.assertEquals

class FormatBytesTest {

    @Test
    fun `zero bytes`() {
        assertEquals("0 B", 0L.formatBytes())
    }

    @Test
    fun `bytes below 1 KB`() {
        assertEquals("512 B", 512L.formatBytes())
        assertEquals("1 B", 1L.formatBytes())
        assertEquals("1023 B", 1023L.formatBytes())
    }

    @Test
    fun `kilobytes`() {
        assertEquals("1 KB", 1024L.formatBytes())
        assertEquals("1.5 KB", (1024L + 512).formatBytes())
    }

    @Test
    fun `megabytes`() {
        assertEquals("1 MB", (1024L * 1024).formatBytes())
        assertEquals("10 MB", (10L * 1024 * 1024).formatBytes())
    }

    @Test
    fun `gigabytes`() {
        assertEquals("1 GB", (1024L * 1024 * 1024).formatBytes())
        assertEquals("4.5 GB", (4L * 1024 * 1024 * 1024 + 512L * 1024 * 1024).formatBytes())
    }

    @Test
    fun `terabytes`() {
        assertEquals("1 TB", (1024L * 1024 * 1024 * 1024).formatBytes())
    }
}
