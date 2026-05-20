package com.tryptz.neuron.download

import com.tryptz.neuron.download.ModelDownloadWorker.Companion.calculateProgress
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Tests for [ModelDownloadWorker.calculateProgress] — the divide-by-zero guard.
 *
 * Regression: when the server omits Content-Length and the descriptor has no
 * declared file size, `totalBytes` is 0; `(downloaded * 100) / total` then
 * threw ArithmeticException, which WorkManager turned into an infinite retry
 * loop. The guard must return a sane value (0) instead of throwing.
 */
class ModelDownloadWorkerProgressTest {

    @Test
    fun `total of zero returns zero and does not throw`() {
        assertEquals(0, calculateProgress(downloaded = 0L, total = 0L))
        assertEquals(0, calculateProgress(downloaded = 5_000_000L, total = 0L))
    }

    @Test
    fun `negative total returns zero and does not throw`() {
        assertEquals(0, calculateProgress(downloaded = 1_000L, total = -1L))
        assertEquals(0, calculateProgress(downloaded = 0L, total = Long.MIN_VALUE))
    }

    @Test
    fun `normal progress is computed correctly`() {
        assertEquals(0, calculateProgress(downloaded = 0L, total = 100L))
        assertEquals(50, calculateProgress(downloaded = 50L, total = 100L))
        assertEquals(100, calculateProgress(downloaded = 100L, total = 100L))
        assertEquals(33, calculateProgress(downloaded = 1L, total = 3L))
    }

    @Test
    fun `progress is clamped to 100 when downloaded exceeds total`() {
        assertEquals(100, calculateProgress(downloaded = 200L, total = 100L))
    }

    @Test
    fun `large multi-gigabyte values do not overflow`() {
        val total = 8L * 1024 * 1024 * 1024 // 8 GiB
        val downloaded = 4L * 1024 * 1024 * 1024 // 4 GiB
        assertEquals(50, calculateProgress(downloaded, total))
    }

    @Test
    fun `notification id is stable and non-negative`() {
        val id1 = ModelDownloadWorker.notificationIdFor("llama-3-8b-q4")
        val id2 = ModelDownloadWorker.notificationIdFor("llama-3-8b-q4")
        assertEquals(id1, id2)
        assert(id1 >= 0)
        // Distinct model ids should generally yield distinct notification ids.
        assert(ModelDownloadWorker.notificationIdFor("model-a") !=
            ModelDownloadWorker.notificationIdFor("model-b"))
    }
}
