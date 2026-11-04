package com.tryptz.neuron.download

import java.net.URI
import java.net.URLDecoder

/**
 * A GGUF file on Hugging Face, parsed from whatever URL the in-app browser
 * produced: a file page (`/blob/`), a raw download (`/resolve/`, often with
 * `?download=true`), on `huggingface.co` or the `hf.co` short host.
 */
data class HfFileRef(val repo: String, val revision: String, val path: String) {
    val fileName: String get() = path.substringAfterLast('/')

    /** Direct download URL; revision is kept so a pinned commit/branch is honoured. */
    val resolveUrl: String get() = "https://huggingface.co/$repo/resolve/${encode(revision)}/${path.split('/').joinToString("/") { encode(it) }}"

    val pageUrl: String get() = "https://huggingface.co/$repo/blob/${encode(revision)}/$path"

    /**
     * llama.cpp split models (`name-00001-of-00003.gguf`) need every part in the
     * same folder and are loaded through the first one. Returns all parts in
     * order, or just this file when it isn't split.
     */
    fun allParts(): List<HfFileRef> {
        val m = SHARD.find(path) ?: return listOf(this)
        val total = m.groupValues[2].toInt()
        val width = m.groupValues[1].length
        val prefix = path.substring(0, m.range.first)
        return (1..total).map { i ->
            copy(path = "$prefix-${i.toString().padStart(width, '0')}-of-${m.groupValues[2]}.gguf")
        }
    }

    val isSplit: Boolean get() = SHARD.containsMatchIn(path)

    companion object {
        private val HOSTS = setOf("huggingface.co", "www.huggingface.co", "hf.co")
        private val SHARD = Regex("""-(\d{5})-of-(\d{5})\.gguf$""", RegexOption.IGNORE_CASE)

        fun isHuggingFaceHost(url: String): Boolean =
            runCatching { URI(url).host?.lowercase() in HOSTS }.getOrDefault(false)

        /** Parses `https://huggingface.co/<owner>/<repo>/(resolve|blob)/<rev>/<path>.gguf`. */
        fun parse(url: String): HfFileRef? {
            val uri = runCatching { URI(url) }.getOrNull() ?: return null
            if (uri.scheme?.lowercase() !in setOf("https", "http")) return null
            if (uri.host?.lowercase() !in HOSTS) return null
            val segs = (uri.rawPath ?: return null).trim('/').split('/').map { decode(it) }
            // owner / repo / kind / revision / path...
            if (segs.size < 5) return null
            val kind = segs[2]
            if (kind != "resolve" && kind != "blob") return null
            val path = segs.drop(4).joinToString("/")
            if (!path.endsWith(".gguf", ignoreCase = true)) return null
            return HfFileRef(repo = "${segs[0]}/${segs[1]}", revision = segs[3], path = path)
        }

        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
        private fun encode(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}
