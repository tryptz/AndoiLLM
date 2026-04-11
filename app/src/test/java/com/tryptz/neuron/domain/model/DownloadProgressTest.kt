package com.tryptz.neuron.domain.model

import org.junit.Test
import kotlin.test.assertEquals

class DownloadProgressTest {

    @Test
    fun `progress fraction is calculated correctly`() {
        val dp = DownloadProgress("test", bytesDownloaded = 500, totalBytes = 1000)
        assertEquals(0.5f, dp.progress)
    }

    @Test
    fun `progress is zero when totalBytes is zero`() {
        val dp = DownloadProgress("test", bytesDownloaded = 0, totalBytes = 0)
        assertEquals(0f, dp.progress)
    }

    @Test
    fun `progress at 100 percent`() {
        val dp = DownloadProgress("test", bytesDownloaded = 1000, totalBytes = 1000)
        assertEquals(1f, dp.progress)
    }

    @Test
    fun `eta is calculated from speed`() {
        val dp = DownloadProgress(
            "test",
            bytesDownloaded = 500,
            totalBytes = 1000,
            speedBytesPerSec = 100
        )
        assertEquals(5L, dp.etaSeconds)
    }

    @Test
    fun `eta is negative when speed is zero`() {
        val dp = DownloadProgress("test", bytesDownloaded = 500, totalBytes = 1000, speedBytesPerSec = 0)
        assertEquals(-1L, dp.etaSeconds)
    }

    @Test
    fun `eta is zero when download is complete`() {
        val dp = DownloadProgress(
            "test",
            bytesDownloaded = 1000,
            totalBytes = 1000,
            speedBytesPerSec = 100
        )
        assertEquals(0L, dp.etaSeconds)
    }
}
