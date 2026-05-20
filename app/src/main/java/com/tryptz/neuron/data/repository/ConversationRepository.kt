package com.tryptz.neuron.data.repository

import com.tryptz.neuron.data.local.dao.ConversationDao
import com.tryptz.neuron.data.local.dao.MessageDao
import com.tryptz.neuron.data.local.entity.ConversationEntity
import com.tryptz.neuron.data.local.entity.MessageEntity
import com.tryptz.neuron.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

/**
 * Abstraction over conversation/message persistence. Implemented by
 * [ConversationRepositoryImpl] and bound via `di/RepositoryModule`.
 */
interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun createConversation(modelId: String?, presetId: String? = null): Conversation
    suspend fun addMessage(message: ChatMessage)
    suspend fun getMessages(conversationId: String): List<ChatMessage>
    suspend fun updateMessage(message: ChatMessage)
    suspend fun deleteConversation(id: String)
    suspend fun deleteMessagesAfter(conversationId: String, timestampMs: Long)
}

class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ConversationRepository {
    private val json = Json { ignoreUnknownKeys = true }

    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeByConversation(conversationId).map { list -> list.map { it.toDomain() } }

    override suspend fun createConversation(modelId: String?, presetId: String?): Conversation {
        val now = System.currentTimeMillis()
        val conv = Conversation(
            id = UUID.randomUUID().toString(),
            title = "New Chat",
            modelId = modelId,
            createdAt = now,
            updatedAt = now,
            presetId = presetId
        )
        conversationDao.upsert(conv.toEntity())
        return conv
    }

    override suspend fun addMessage(message: ChatMessage) {
        // Capture count BEFORE insert so the "first user message" check is accurate.
        val priorCount = messageDao.countInConversation(message.conversationId)
        messageDao.insert(message.toEntity())

        if (message.role == MessageRole.USER && priorCount == 0) {
            // First user message defines the conversation title.
            conversationDao.updateTitle(
                id = message.conversationId,
                title = if (message.content.length <= 60) message.content
                        else message.content.take(57) + "...",
                updatedAt = message.timestampMs
            )
        } else {
            // Assistant replies (and later user messages) only bump the timestamp.
            conversationDao.touchConversation(message.conversationId, message.timestampMs)
        }
    }

    override suspend fun getMessages(conversationId: String): List<ChatMessage> =
        messageDao.getByConversation(conversationId).map { it.toDomain() }

    override suspend fun updateMessage(message: ChatMessage) {
        messageDao.update(message.toEntity())
    }

    override suspend fun deleteConversation(id: String) {
        conversationDao.deleteById(id)
    }

    override suspend fun deleteMessagesAfter(conversationId: String, timestampMs: Long) {
        messageDao.deleteAfter(conversationId, timestampMs)
    }

    // ── Mappers ──

    private fun ConversationEntity.toDomain() = Conversation(id, title, modelId, createdAt, updatedAt, presetId)
    private fun Conversation.toEntity() = ConversationEntity(id, title, modelId, presetId, createdAt, updatedAt)

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id, conversationId = conversationId,
        role = runCatching { MessageRole.valueOf(role) }.getOrDefault(MessageRole.USER),
        content = content,
        thinkingContent = thinkingContent,
        imageUris = imageUrisJson?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) } ?: emptyList(),
        timestampMs = timestampMs, tokenCount = tokenCount,
        tokensPerSec = tokensPerSec, parentId = parentId
    )

    private fun ChatMessage.toEntity() = MessageEntity(
        id = id, conversationId = conversationId,
        role = role.name, content = content,
        thinkingContent = thinkingContent,
        imageUrisJson = if (imageUris.isNotEmpty()) json.encodeToString(imageUris) else null,
        timestampMs = timestampMs, tokenCount = tokenCount,
        tokensPerSec = tokensPerSec, parentId = parentId
    )
}
