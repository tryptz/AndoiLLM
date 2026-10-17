package com.tryptz.neuron.download

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.tryptz.neuron.data.local.datastore.SettingsDataStore
import com.tryptz.neuron.data.repository.ModelRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.head
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** What the browser learned about a file before downloading it. */
sealed interface PreflightResult {
    /** [totalBytes] covers every part of a split model; null when the server didn't say. */
    data class Ok(val parts: List<HfFileRef>, val totalBytes: Long?, val freeBytes: Long) : PreflightResult
    /** Gated / private repo: Neuron downloads anonymously, so this can't work. */
    data class Gated(val ref: HfFileRef) : PreflightResult
    data class NotFound(val ref: HfFileRef) : PreflightResult
    data class Failed(val message: String) : PreflightResult
}

data class ActiveDownload(val name: String, val downloaded: Long, val total: Long, val state: WorkInfo.State)

/**
 * Downloads started from the in-app Hugging Face browser. They use the same
 * WorkManager worker as the Model Manager (resume, Wi-Fi-only, foreground
 * notification, auto-registration in the library).
 */
@Singleton
class ModelDownloads @Inject constructor(
    private val workManager: WorkManager,
    private val settings: SettingsDataStore,
    private val repository: ModelRepository,
    private val http: HttpClient
) {
    /** Every model download in flight or recently finished, for the browser's progress strip. */
    val active: Flow<List<ActiveDownload>> =
        workManager.getWorkInfosByTagFlow(ModelDownloadWorker.TAG_ALL).map { infos ->
            infos.filter { !it.state.isFinished || it.state == WorkInfo.State.FAILED }.map { info ->
                ActiveDownload(
                    name = info.tags.firstOrNull { it.startsWith(ModelDownloadWorker.TAG_NAME_PREFIX) }
                        ?.removePrefix(ModelDownloadWorker.TAG_NAME_PREFIX) ?: "model",
                    downloaded = info.progress.getLong(ModelDownloadWorker.KEY_DOWNLOADED_BYTES, 0),
                    total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL_BYTES, 0),
                    state = info.state
                )
            }
        }

    /**
     * HEADs every part (following HF's redirect to its CDN) to learn the size and
     * to catch gated repos before a multi-GB download fails at byte zero.
     */
    suspend fun preflight(ref: HfFileRef): PreflightResult = withContext(Dispatchers.IO) {
        val parts = ref.allParts()
        var total = 0L
        var sizeKnown = true
        for (part in parts) {
            val resp = runCatching { http.head(part.resolveUrl) }.getOrElse {
                return@withContext PreflightResult.Failed(it.message ?: "Network error")
            }
            when (resp.status.value) {
                401, 403 -> return@withContext PreflightResult.Gated(part)
                404 -> return@withContext PreflightResult.NotFound(part)
            }
            if (!resp.status.isSuccess()) return@withContext PreflightResult.Failed("HTTP ${resp.status.value}")
            val len = resp.headers["X-Linked-Size"]?.toLongOrNull() ?: resp.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (len == null) sizeKnown = false else total += len
        }
        PreflightResult.Ok(parts, if (sizeKnown) total else null, repository.modelsDir.usableSpace)
    }

    /**
     * Enqueues [parts] as one chain. For a split model the first part (the one
     * llama.cpp opens) downloads last and is the only one registered, so the
     * model appears in the library only when every part is on disk.
     */
    suspend fun enqueue(parts: List<HfFileRef>) {
        require(parts.isNotEmpty())
        val wifiOnly = settings.isWifiOnlyDownloads.first()
        val first = parts.first()
        val displayName = "${first.repo.substringAfterLast('/')} / ${first.fileName}"
        val requests = (parts.drop(1) + first).map { part ->
            val isEntry = part == first
            ModelDownloadWorker.buildRequest(
                modelId = syntheticId(part),
                url = part.resolveUrl,
                filename = part.fileName,
                displayName = if (isEntry) displayName else "$displayName (${part.fileName})",
                sourceRepo = part.repo,
                wifiOnly = wifiOnly,
                registerAsModel = isEntry
            )
        }
        val chain = workManager.beginUniqueWork("hf_${syntheticId(first)}", ExistingWorkPolicy.KEEP, requests.first())
        requests.drop(1).fold(chain) { c, r -> c.then(r) }.enqueue()
        Timber.i("[op=hf_browser_enqueue] repo=${first.repo} file=${first.path} parts=${parts.size}")
    }

    private fun syntheticId(ref: HfFileRef) = "hf:${ref.repo.replace('/', '_')}:${ref.path}"
}
