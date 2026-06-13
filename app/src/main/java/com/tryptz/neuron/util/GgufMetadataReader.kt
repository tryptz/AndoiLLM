package com.tryptz.neuron.util

import android.content.Context
import android.net.Uri
import com.tryptz.neuron.domain.model.ChatTemplate
import com.tryptz.neuron.domain.model.Quantization
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Parsed metadata from a GGUF file header.
 */
data class GgufMetadata(
    val name: String? = null,
    val architecture: String? = null,
    val contextLength: Int? = null,
    val quantization: Quantization? = null,
    val fileType: Int? = null,
    val parameterCount: Long? = null,
    val chatTemplate: ChatTemplate? = null
) {
    val inferredChatTemplate: ChatTemplate
        get() = chatTemplate ?: architecture?.let { mapArchToTemplate(it) } ?: ChatTemplate.CHATML

    val displayQuantization: String
        get() = quantization?.label ?: fileType?.let { "type_$it" } ?: "Unknown"
}

/**
 * Reads metadata from GGUF file headers.
 *
 * GGUF format (v3):
 *   magic[4] "GGUF" | version:u32 | tensor_count:u64 | metadata_kv_count:u64
 *   followed by metadata_kv_count key-value pairs.
 */
object GgufMetadataReader {

    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian

    fun read(context: Context, uri: Uri): GgufMetadata? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                parseHeader(stream)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Reads metadata from a GGUF file already on app-private storage
     *  (e.g. just downloaded by ModelDownloadWorker). */
    fun read(file: java.io.File): GgufMetadata? {
        return try {
            file.inputStream().buffered().use { stream -> parseHeader(stream) }
        } catch (_: Exception) {
            null
        }
    }

    /** Fallback quant detection from the file name (e.g. "...-Q4_K_M.gguf")
     *  for the many file_type codes [mapFileTypeToQuantization] doesn't cover. */
    fun quantLabelFromFileName(fileName: String): String? =
        Regex("""(?i)(IQ\d+_[A-Z0-9_]+|Q\d+_K_[SML]|Q\d+_K|Q\d+_\d|BF16|F16|F32)""")
            .find(fileName)?.value?.uppercase()

    private fun parseHeader(stream: InputStream): GgufMetadata? {
        val buf = ByteBuffer.wrap(readBytes(stream, 4 + 4 + 8 + 8) ?: return null)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.int
        if (magic != GGUF_MAGIC) return null

        val version = buf.int
        if (version < 2) return null

        val tensorCount = buf.long
        val kvCount = buf.long

        var name: String? = null
        var architecture: String? = null
        var contextLength: Int? = null
        var fileType: Int? = null
        var blockCount: Int? = null
        var chatTemplateStr: String? = null

        for (i in 0 until kvCount.coerceAtMost(512)) {
            val key = readString(stream) ?: break
            val valueType = readU32(stream) ?: break

            when (key) {
                "general.architecture" -> architecture = readTypedString(stream, valueType)
                "general.name" -> name = readTypedString(stream, valueType)
                "general.file_type" -> fileType = readTypedInt(stream, valueType)
                else -> {
                    if (key.endsWith(".context_length")) {
                        contextLength = readTypedInt(stream, valueType)
                    } else if (key.endsWith(".block_count")) {
                        blockCount = readTypedInt(stream, valueType)
                    } else if (key == "tokenizer.chat_template") {
                        chatTemplateStr = readTypedString(stream, valueType)
                    } else {
                        skipValue(stream, valueType) ?: break
                    }
                }
            }
        }

        val quantization = fileType?.let { mapFileTypeToQuantization(it) }
        val chatTemplate = chatTemplateStr?.let { detectChatTemplate(it) }

        return GgufMetadata(
            name = name,
            architecture = architecture,
            contextLength = contextLength,
            quantization = quantization,
            fileType = fileType,
            parameterCount = blockCount?.let { estimateParams(it, architecture) },
            chatTemplate = chatTemplate
        )
    }

    // ── Binary reading helpers ──

    private fun readBytes(stream: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = stream.read(buf, read, count - read)
            if (n < 0) return null
            read += n
        }
        return buf
    }

    private fun readU32(stream: InputStream): Int? {
        val buf = ByteBuffer.wrap(readBytes(stream, 4) ?: return null)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return buf.int
    }

    private fun readU64(stream: InputStream): Long? {
        val buf = ByteBuffer.wrap(readBytes(stream, 8) ?: return null)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        return buf.long
    }

    private fun readString(stream: InputStream): String? {
        val len = readU64(stream) ?: return null
        if (len > 65536) return null
        val bytes = readBytes(stream, len.toInt()) ?: return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun readTypedString(stream: InputStream, valueType: Int): String? {
        return if (valueType == 8) readString(stream) else { skipValue(stream, valueType); null }
    }

    private fun readTypedInt(stream: InputStream, valueType: Int): Int? {
        return when (valueType) {
            0 -> readBytes(stream, 1)?.get(0)?.toInt()?.and(0xFF) // UINT8
            2 -> readBytes(stream, 2)?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt().and(0xFFFF) } // UINT16
            4 -> readU32(stream) // UINT32
            5 -> readU32(stream) // INT32
            10 -> readU64(stream)?.toInt() // UINT64
            else -> { skipValue(stream, valueType); null }
        }
    }

    private fun skipValue(stream: InputStream, valueType: Int): Boolean? {
        val bytesToSkip = when (valueType) {
            0, 1 -> 1L   // UINT8, INT8
            2, 3 -> 2L   // UINT16, INT16
            4, 5, 6 -> 4L // UINT32, INT32, FLOAT32
            7 -> 1L       // BOOL
            8 -> {         // STRING
                val len = readU64(stream) ?: return null
                len
            }
            9 -> {         // ARRAY
                val arrType = readU32(stream) ?: return null
                val arrLen = readU64(stream) ?: return null
                for (j in 0 until arrLen.coerceAtMost(65536)) {
                    skipValue(stream, arrType) ?: return null
                }
                return true
            }
            10, 11 -> 8L  // UINT64, INT64
            12 -> 8L       // FLOAT64
            else -> return null
        }
        readBytes(stream, bytesToSkip.toInt()) ?: return null
        return true
    }

    // ── Mapping helpers ──

    private fun mapFileTypeToQuantization(fileType: Int): Quantization? = when (fileType) {
        0 -> Quantization.FP16     // F32 (closest match)
        1 -> Quantization.FP16     // F16
        2 -> Quantization.Q4_0     // Q4_0
        7 -> Quantization.Q8_0     // Q8_0
        14 -> Quantization.Q4_K_M  // Q4_K_S
        15 -> Quantization.Q4_K_M  // Q4_K_M
        8 -> Quantization.Q8_0     // Q8_1
        else -> null               // IQ types, Q2_K, Q3_K, Q5_K, Q6_K, etc.
    }

    private fun detectChatTemplate(template: String): ChatTemplate? = when {
        "im_start" in template -> ChatTemplate.CHATML
        "start_header_id" in template -> ChatTemplate.LLAMA3
        "start_of_turn" in template -> ChatTemplate.GEMMA
        "<|system|>" in template || "<|user|>" in template -> ChatTemplate.PHI
        "[INST]" in template -> ChatTemplate.MISTRAL
        else -> null
    }

    private fun estimateParams(blockCount: Int, architecture: String?): Long {
        // Rough estimates based on typical architectures
        val paramsPerBlock = when (architecture) {
            "llama" -> 200_000_000L / 32  // ~6.25M per block for 7B-class
            "gemma", "gemma2" -> 200_000_000L / 28
            else -> 200_000_000L / 32
        }
        return blockCount * paramsPerBlock
    }
}

private fun mapArchToTemplate(arch: String): ChatTemplate = when (arch.lowercase()) {
    "llama" -> ChatTemplate.LLAMA3
    "gemma", "gemma2" -> ChatTemplate.GEMMA
    "phi", "phi3" -> ChatTemplate.PHI
    "qwen", "qwen2" -> ChatTemplate.CHATML
    "mistral" -> ChatTemplate.MISTRAL
    else -> ChatTemplate.CHATML
}
