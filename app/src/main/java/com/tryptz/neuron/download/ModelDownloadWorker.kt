package com.tryptz.neuron.download

import android.app.NotificationManager
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
        private const val NOTIFICATION_ID = 1001

        fun buildRequest(modelId: String): OneTimeWorkRequest {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresStorageNotLow(true)
                .build()

            return OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_MODEL_ID to modelId))
                .addTag("model_download_$modelId")
                .build()
        }
    }

    override suspend fun doWork(): Result {
        val modelId = inputData.getString(KEY_MODEL_ID) ?: return Result.failure()
        val descriptor = ModelRegistry.getById(modelId) ?: return Result.failure()

        val url = "https://huggingface.co/${descriptor.huggingFaceRepo}/resolve/main/${descriptor.huggingFaceFile}"
        val outputFile = File(modelRepository.modelsDir, descriptor.huggingFaceFile)

        Timber.i("Starting download: ${descriptor.name} from $url")

        setForeground(createForegroundInfo(descriptor.name, 0))

        return try {
            httpClient.prepareGet(url).execute { response ->
                val totalBytes = response.contentLength() ?: (descriptor.fileSizeMb.toLong() * 1024 * 1024)
                val channel = response.bodyAsChannel()
                var downloadedBytes = 0L
                val buffer = ByteArray(8192)
                var lastProgressUpdate = 0L
                var lastSpeedCalcTime = System.currentTimeMillis()
                var lastSpeedCalcBytes = 0L

                FileOutputStream(outputFile).use { fos ->
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

                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            setProgress(workDataOf(
                                KEY_PROGRESS to progress,
                                KEY_DOWNLOADED_BYTES to downloadedBytes,
                                KEY_TOTAL_BYTES to totalBytes,
                                KEY_SPEED_BPS to speed
                            ))
                            setForeground(createForegroundInfo(descriptor.name, progress))
                            lastProgressUpdate = now
                        }
                    }
                }

                modelRepository.registerInstalled(
                    descriptorId = modelId,
                    filePath = outputFile.absolutePath,
                    sizeBytes = downloadedBytes
                )

                Timber.i("Download complete: ${descriptor.name}")
                Result.success(workDataOf(KEY_MODEL_ID to modelId))
            }
        } catch (e: Exception) {
            Timber.e(e, "Download failed: ${descriptor.name}")
            outputFile.delete()
            Result.retry()
        }
    }

    private fun createForegroundInfo(modelName: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, NeuronApp.CHANNEL_DOWNLOADS)
            .setContentTitle("Downloading $modelName")
            .setContentText("$progress%")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }
}
