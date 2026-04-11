package com.tryptz.neuron.data.repository

import com.tryptz.neuron.data.local.dao.ConversationDao
import com.tryptz.neuron.data.local.dao.MessageDao
import com.tryptz.neuron.data.local.entity.ConversationEntity
import com.tryptz.neuron.data.local.entity.MessageEntity
import com.tryptz.neuron.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationRepository @Inject constructor(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
        messageDao.observeByConversation(conversationId).map { list -> list.map { it.toDomain() } }

    suspend fun createConversation(modelId: String?, presetId: String? = null): Conversation {
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

    suspend fun addMessage(message: ChatMessage) {
        messageDao.insert(message.toEntity())
        conversationDao.updateTitle(
            id = message.conversationId,
            title = if (message.role == MessageRole.USER && message.content.length <= 60) message.content
                    else message.content.take(57) + "...",
            updatedAt = message.timestampMs
        )
    }

    suspend fun updateMessage(message: ChatMessage) {
        messageDao.update(message.toEntity())
    }

    suspend fun deleteConversation(id: String) {
        conversationDao.deleteById(id)
    }

    suspend fun deleteMessagesAfter(conversationId: String, timestampMs: Long) {
        messageDao.deleteAfter(conversationId, timestampMs)
    }

    // ── Mappers ──

    private fun ConversationEntity.toDomain() = Conversation(id, title, modelId, createdAt, updatedAt, presetId)
    private fun Conversation.toEntity() = ConversationEntity(id, title, modelId, presetId, createdAt, updatedAt)

    private fun MessageEntity.toDomain() = ChatMessage(
        id = id, conversationId = conversationId,
        role = MessageRole.valueOf(role), content = content,
        thinkingContent = thinkingContent,
        imageUris = imageUrisJson?.let { runCatching { json.decodeFromString<List<String>>(it) }.getOrDefault(emptyList()) } ?: emptyList(),
        timestampMs = timestampMs, tokenCount = tokenCount,
        tokensPerSec = tokensPerSec, parentId = parentId
    )

    private fun ChatMessage.toEntity() = MessageEntity(
        id = id, conversationId = conversationId,
        role = role.name, content = content,
        thinkingContent = thinkingContent,
        imageUrisJson = if (imageUris.isNotEmpty()) json.encodeToString(kotlinx.serialization.builtins.ListSerializer(kotlinx.serialization.builtins.serializer<String>()), imageUris) else null,
        timestampMs = timestampMs, tokenCount = tokenCount,
        tokensPerSec = tokensPerSec, parentId = parentId
    )
}
