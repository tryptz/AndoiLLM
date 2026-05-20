package com.tryptz.neuron.data.remote

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hugging Face Hub REST client for GGUF model discovery + file enumeration.
 *
 * Uses the open `/api/models` and `/api/models/<repo>/tree/main` endpoints —
 * no auth required for public repos. Manual `Json.decodeFromString` rather
 * than Ktor's ContentNegotiation plugin so we don't have to reconfigure
 * the shared HttpClient.
 */
@Singleton
class HuggingFaceClient @Inject constructor(
    private val http: HttpClient
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Search Hugging Face Hub for GGUF-tagged text-generation models.
     * Empty query returns the top-ranked repos for [sort].
     */
    suspend fun search(query: String, sort: HfSort, limit: Int = 30): List<HfRepoSummary> {
        val q = if (query.isBlank()) "" else "&search=${URLEncoder.encode(query.trim(), "UTF-8")}"
        val url = "https://huggingface.co/api/models" +
            "?filter=gguf&sort=${sort.apiKey}&direction=-1&limit=$limit$q"
        Timber.i("[op=hf_search] sort=${sort.apiKey} q='$query' url=$url")
        val body = http.get(url).bodyAsText()
        return runCatching { json.decodeFromString<List<HfRepoSummary>>(body) }
            .onFailure { Timber.e(it, "[op=hf_search_parse_fail] body_len=${body.length}") }
            .getOrDefault(emptyList())
    }

    /** List .gguf files in a repo, sorted ascending by file size. */
    suspend fun listGgufFiles(repo: String): List<HfFile> {
        val url = "https://huggingface.co/api/models/$repo/tree/main"
        Timber.i("[op=hf_list_files] repo=$repo")
        val body = http.get(url).bodyAsText()
        val all = runCatching { json.decodeFromString<List<HfFile>>(body) }
            .onFailure { Timber.e(it, "[op=hf_list_files_parse_fail] repo=$repo") }
            .getOrDefault(emptyList())
        return all.filter { it.type == "file" && it.path.endsWith(".gguf") }
            .sortedBy { it.size ?: Long.MAX_VALUE }
    }
}

@Serializable
data class HfRepoSummary(
    val id: String,
    val downloads: Int = 0,
    val likes: Int = 0,
    val lastModified: String? = null,
    val createdAt: String? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class HfFile(
    val type: String = "file",
    val path: String,
    val size: Long? = null
)

enum class HfSort(val apiKey: String, val label: String) {
    POPULAR("downloads", "Popular"),
    TRENDING("trendingScore", "Trending"),
    UPDATED("lastModified", "Updated"),
    NEW("createdAt", "Newest")
}
