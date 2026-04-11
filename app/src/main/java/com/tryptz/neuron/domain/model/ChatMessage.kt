package com.tryptz.neuron.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: MessageRole,
    val content: String,
    val thinkingContent: String? = null,
    val imageUris: List<String> = emptyList(),
    val timestampMs: Long = System.currentTimeMillis(),
    val tokenCount: Int? = null,
    val tokensPerSec: Float? = null,
    val parentId: String? = null,
    val codeBlocks: List<CodeBlock> = emptyList()
)

@Immutable
data class CodeBlock(
    val id: String,
    val language: CodeLanguage,
    val code: String,
    val output: CodeOutput? = null
)

@Immutable
data class CodeOutput(
    val stdout: String = "",
    val stderr: String = "",
    val returnValue: String? = null,
    val executionTimeMs: Long = 0,
    val memoryUsedBytes: Long = 0,
    val imageBase64: String? = null,
    val htmlPreview: String? = null,
    val error: CodeError? = null
)

@Immutable
data class CodeError(
    val type: ErrorType,
    val message: String,
    val line: Int? = null
)
