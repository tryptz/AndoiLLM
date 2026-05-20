package com.tryptz.neuron.download

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.*
import com.tryptz.neuron.NeuronApp
import com.tryptz.neuron.data.model.ModelRegistry
import com.tryptz.neuron.data.repository.ModelRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val modelRepository: ModelRepository,
    private val httpClient: HttpClient
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val KEY_MODEL_ID = "model_id"
        const val KEY_PROGRESS = "progress"
        const val KEY_DOWNLOADED_BYTES = "downloaded_bytes"
        const val KEY_TOTAL_BYTES = "total_bytes"
        const val KEY_SPEED_BPS = "speed_bps"
        // Optional overrides for HF-browser-initiated downloads — when set, the
        // worker uses these instead of the curated ModelRegistry lookup.
        const val KEY_URL = "url"
        const val KEY_FILENAME = "filename"
        const val KEY_DISPLAY_NAME = "display_name"
        const val KEY_SOURCE_REPO = "source_repo"

        /**
         * Computes a download completion percentage (0..100) without throwing.
         *
         * Guards against a divide-by-zero when [total] is unknown (<= 0), which
         * happens when the server omits Content-Length and the descriptor has no
         * declared file size. In that case the percentage is unknown so 0 is
         * returned and callers should show an indeterminate progress bar.
         */
        fun calculateProgress(downloaded: Long, total: Long): Int {
            if (total <= 0L) return 0
            val pct = (downloaded * 100) / total
            return pct.coerceIn(0L, 100L).toInt()
        }

        /** Derives a stable, non-negative notification id from the model id so
         *  concurrent downloads each get their own notification. */
        fun notificationIdFor(modelId: String): Int = modelId.hashCode() and Int.MAX_VALUE

        fun buildRequest(
            modelId: String,
            url: String? = null,
            filename: String? = null,
            displayName: String? = null,
            sourceRepo: String? = null
        ): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(
                    KEY_MODEL_ID to modelId,
                    KEY_URL to url,
                    KEY_FILENAME to filename,
                    KEY_DISPLAY_NAME to displayName,
                    KEY_SOURCE_REPO to sourceRepo
                ))
                .addTag("model_download_$modelId")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val urlOverride = inputData.getString(KEY_URL)
        val filenameOverride = inputData.getString(KEY_FILENAME)
        val displayName = inputData.getString(KEY_DISPLAY_NAME)
        val sourceRepo = inputData.getString(KEY_SOURCE_REPO)
        val isHfDownload = urlOverride != null && filenameOverride != null

        val url: String
        val outputFileName: String
        val modelName: String
        val curatedDescriptorSize: Long

        if (isHfDownload) {
            url = urlOverride!!
            outputFileName = filenameOverride!!
            modelName = displayName ?: outputFileName
            curatedDescriptorSize = 0L
        } else {
            val descriptor = ModelRegistry.getByRawId(modelId) ?: return Result.failure()
            url = "https://huggingface.co/${descriptor.huggingFaceRepo}/resolve/main/${descriptor.huggingFaceFile}"
            outputFileName = descriptor.huggingFaceFile
            modelName = descriptor.name
            curatedDescriptorSize = descriptor.fileSizeMb.toLong() * 1024 * 1024
        }
        val outputFile = File(modelRepository.modelsDir, outputFileName)

        // Resume support: if a partial file exists, ask the server to continue
        // from where we left off via a Range header.
        val existingBytes = if (outputFile.exists()) outputFile.length() else 0L

        Timber.i("[op=download_start] name=\"$modelName\" url=$url hf=$isHfDownload resume_from=$existingBytes")

        setForeground(createForegroundInfo(modelName, modelId, 0))

        return try {
            httpClient.prepareGet(url) {
                if (existingBytes > 0L) {
                    headers.append(HttpHeaders.Range, "bytes=$existingBytes-")
                }
            }.execute { response ->
                // 206 Partial Content => server honored the Range request and we
                // append. Anything else (typically 200) => restart from scratch.
                val isResume = existingBytes > 0L &&
                    response.status == HttpStatusCode.PartialContent

                if (isResume) {
                    Timber.i("Server supports resume; continuing from $existingBytes bytes")
                } else if (existingBytes > 0L) {
                    Timber.i("Server ignored Range (status ${response.status}); restarting download")
                }

                // For a 206, Content-Length is the *remaining* bytes, so the full
                // size is contentLength + existingBytes. For a 200 it's the whole file.
                val responseLength = response.contentLength()
                val totalBytes = when {
                    responseLength == null -> curatedDescriptorSize
                    isResume -> responseLength + existingBytes
                    else -> responseLength
                }

                val channel = response.bodyAsChannel()
                var downloadedBytes = if (isResume) existingBytes else 0L
                val buffer = ByteArray(8192)
                var lastProgressUpdate = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var lastSpeedCalcBytes = downloadedBytes

                FileOutputStream(outputFile, /* append = */ isResume).use { fos ->
                    while (!channel.isClosedForRead) {
                        val read = channel.readAvailable(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        downloadedBytes += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgressUpdate > 500) {
                            val elapsed = (now - lastSpeedCalcTime).coerceAtLeast(1)
                            val speed = ((downloadedBytes - lastSpeedCalcBytes) * 1000) / elapsed
                            lastSpeedCalcTime = now
                            lastSpeedCalcBytes = downloadedBytes

                            val progress = calculateProgress(downloadedBytes, totalBytes)
                            setProgress(workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_DOWNLOADED_BYTES to downloadedBytes,
                                KEY_TOTAL_BYTES to totalBytes,
                                KEY_SPEED_BPS to speed
                            ))
                            setForeground(createForegroundInfo(modelName, modelId, progress, totalBytes))
                            lastProgressUpdate = now
                        }
                    }
                }

                if (isHfDownload) {
                    // HF-initiated download → register as a LocalModelEntity so
                    // it appears in the user's library (no curated descriptor).
                    modelRepository.registerDownloadedGguf(
                        filePath = outputFile.absolutePath,
                        displayName = modelName,
                        sourceRepo = sourceRepo
                    )
                } else {
                    modelRepository.registerInstalled(
                        descriptorId = modelId,
                        filePath = outputFile.absolutePath,
                        sizeBytes = downloadedBytes
                    )
                }

                Timber.i("[op=download_complete] name=\"$modelName\" bytes=$downloadedBytes hf=$isHfDownload")
                Result.success(workDataOf(KEY_MODEL_ID to modelId))
            }
        } catch (e: Exception) {
            // Keep the partial file on disk so the next retry can resume from it
            // instead of re-downloading multiple GB from byte 0.
            Timber.e(e, "[op=download_fail] name=\"$modelName\" partial_kept_for_resume")
            Result.retry()
        }
    }

    private fun createForegroundInfo(
        modelName: String,
        modelId: String,
        progress: Int,
        totalBytes: Long = 0L
    ): ForegroundInfo {
        // Indeterminate when we don't yet know the total size or progress is at 0%.
        val indeterminate = totalBytes <= 0L || progress == 0
        val notification = NotificationCompat.Builder(applicationContext, NeuronApp.CHANNEL_DOWNLOADS)
            .setContentTitle("Downloading $modelName")
            .setContentText(if (indeterminate) "Starting…" else "$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, indeterminate)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(notificationIdFor(modelId), notification)
    }
}
