package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.code.sandbox.CodeExecutor
import com.tryptz.neuron.data.repository.ConversationRepository
import com.tryptz.neuron.domain.model.*
import com.tryptz.neuron.inference.backend.InferenceEngine
import com.tryptz.neuron.inference.backend.InferenceEvent
import com.tryptz.neuron.util.parseCodeBlocks
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.UUID
import javax.inject.Inject

/**
 * Orchestrates streaming inference and persists the final assistant
 * message with parsed code blocks.
 */
sealed class GenerationResult {
    data class Streaming(
        val content: String,
        val thinking: String,
        val isThinking: Boolean,
        val tokSec: Float,
        val tokenCount: Int
    ) : GenerationResult()

    data class Completed(val message: ChatMessage) : GenerationResult()
    data class Error(val message: String) : GenerationResult()
}

class GenerateResponseUseCase @Inject constructor(
    private val inferenceEngine: InferenceEngine,
    private val conversationRepo: ConversationRepository,
    private val codeExecutor: CodeExecutor
) {
    operator fun invoke(
        messages: List<ChatMessage>,
        settings: InferenceSettings,
        conversationId: String
    ): Flow<GenerationResult> = flow {
        var fullContent = ""
        var fullThinking = ""
        var lastTokSec = 0f
        var tokenCount = 0

        inferenceEngine.generateStream(messages, settings).collect { event ->
            when (event) {
                is InferenceEvent.Started -> {}
                is InferenceEvent.Token -> {
                    fullContent += event.text
                    lastTokSec = event.tokSec
                    tokenCount++
                    emit(GenerationResult.Streaming(fullContent, fullThinking, false, lastTokSec, tokenCount))
                }
                is InferenceEvent.ThinkingToken -> {
                    fullThinking += event.text
                    emit(GenerationResult.Streaming(fullContent, fullThinking, true, lastTokSec, tokenCount))
                }
                is InferenceEvent.Completed -> {
                    val msgId = UUID.randomUUID().toString()
                    // Fence tag first (the model said what language it wrote);
                    // heuristic detection only for blank/unknown tags.
                    val codeBlocks = parseCodeBlocks(msgId, fullContent, codeExecutor::detectLanguage)
                    val msg = ChatMessage(
                        id = msgId,
                        conversationId = conversationId,
                        role = MessageRole.ASSISTANT,
                        content = fullContent,
                        thinkingContent = fullThinking.ifBlank { null },
                        tokenCount = tokenCount,
                        tokensPerSec = lastTokSec,
                        codeBlocks = codeBlocks
                    )
                    conversationRepo.addMessage(msg)
                    emit(GenerationResult.Completed(msg))
                }
                is InferenceEvent.Error -> emit(GenerationResult.Error(event.message))
            }
        }
    }
}
