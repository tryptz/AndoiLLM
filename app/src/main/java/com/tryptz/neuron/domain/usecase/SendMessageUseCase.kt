package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.data.repository.ConversationRepository
import com.tryptz.neuron.domain.model.ChatMessage
import com.tryptz.neuron.domain.model.MessageRole
import java.util.UUID
import javax.inject.Inject

/**
 * Adds a user message to a conversation, creating the conversation
 * if it doesn't exist yet.
 */
class SendMessageUseCase @Inject constructor(
    private val conversationRepo: ConversationRepository
) {
    suspend operator fun invoke(
        text: String,
        conversationId: String?,
        modelId: String?,
        imageUris: List<String> = emptyList()
    ): Pair<String, ChatMessage> {
        val convId = conversationId ?: conversationRepo.createConversation(
            modelId = modelId
        ).id

        val userMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            conversationId = convId,
            role = MessageRole.USER,
            content = text,
            imageUris = imageUris
        )
        conversationRepo.addMessage(userMsg)

        return convId to userMsg
    }
}
