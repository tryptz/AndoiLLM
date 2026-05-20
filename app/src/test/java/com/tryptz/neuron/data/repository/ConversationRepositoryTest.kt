package com.tryptz.neuron.data.repository

import com.tryptz.neuron.data.local.dao.ConversationDao
import com.tryptz.neuron.data.local.dao.MessageDao
import com.tryptz.neuron.data.local.entity.ConversationEntity
import com.tryptz.neuron.data.local.entity.MessageEntity
import com.tryptz.neuron.domain.model.ChatMessage
import com.tryptz.neuron.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [ConversationRepositoryImpl] title/touch logic (task 1)
 * and the guarded enum mapping (task 6).
 */
class ConversationRepositoryTest {

    private class FakeConversationDao : ConversationDao {
        val conversations = mutableMapOf<String, ConversationEntity>()
        var touchCount = 0
        var updateTitleCount = 0

        override fun observeAll(): Flow<List<ConversationEntity>> = flowOf(conversations.values.toList())
        override suspend fun getById(id: String): ConversationEntity? = conversations[id]
        override suspend fun upsert(conversation: ConversationEntity) {
            conversations[conversation.id] = conversation
        }
        override suspend fun delete(conversation: ConversationEntity) {
            conversations.remove(conversation.id)
        }
        override suspend fun deleteById(id: String) {
            conversations.remove(id)
        }
        override suspend fun updateTitle(id: String, title: String, updatedAt: Long) {
            updateTitleCount++
            conversations[id]?.let { conversations[id] = it.copy(title = title, updatedAt = updatedAt) }
        }
        override suspend fun touchConversation(id: String, updatedAt: Long) {
            touchCount++
            conversations[id]?.let { conversations[id] = it.copy(updatedAt = updatedAt) }
        }
    }

    private class FakeMessageDao : MessageDao {
        val messages = mutableListOf<MessageEntity>()

        override fun observeByConversation(conversationId: String): Flow<List<MessageEntity>> =
            flowOf(messages.filter { it.conversationId == conversationId })
        override suspend fun getByConversation(conversationId: String): List<MessageEntity> =
            messages.filter { it.conversationId == conversationId }.sortedBy { it.timestampMs }
        override suspend fun insert(message: MessageEntity) {
            messages.removeAll { it.id == message.id }
            messages.add(message)
        }
        override suspend fun update(message: MessageEntity) {
            val i = messages.indexOfFirst { it.id == message.id }
            if (i >= 0) messages[i] = message
        }
        override suspend fun deleteById(id: String) {
            messages.removeAll { it.id == id }
        }
        override suspend fun deleteAfter(conversationId: String, afterTimestamp: Long) {
            messages.removeAll { it.conversationId == conversationId && it.timestampMs > afterTimestamp }
        }
        override suspend fun countInConversation(conversationId: String): Int =
            messages.count { it.conversationId == conversationId }
    }

    private fun newRepo(): Triple<ConversationRepositoryImpl, FakeConversationDao, FakeMessageDao> {
        val convDao = FakeConversationDao()
        val msgDao = FakeMessageDao()
        return Triple(ConversationRepositoryImpl(convDao, msgDao), convDao, msgDao)
    }

    private fun userMsg(convId: String, content: String, ts: Long) = ChatMessage(
        id = "u-$ts", conversationId = convId, role = MessageRole.USER, content = content, timestampMs = ts
    )

    private fun assistantMsg(convId: String, content: String, ts: Long) = ChatMessage(
        id = "a-$ts", conversationId = convId, role = MessageRole.ASSISTANT, content = content, timestampMs = ts
    )

    @Test
    fun `first user message sets the conversation title`() = runTest {
        val (repo, convDao, _) = newRepo()
        val conv = repo.createConversation(modelId = null)

        repo.addMessage(userMsg(conv.id, "What is Kotlin?", ts = 1000))

        assertEquals("What is Kotlin?", convDao.conversations[conv.id]?.title)
        assertEquals(1000L, convDao.conversations[conv.id]?.updatedAt)
        assertEquals(1, convDao.updateTitleCount)
        assertEquals(0, convDao.touchCount)
    }

    @Test
    fun `assistant message does not change the title but bumps updatedAt`() = runTest {
        val (repo, convDao, _) = newRepo()
        val conv = repo.createConversation(modelId = null)

        repo.addMessage(userMsg(conv.id, "First question", ts = 1000))
        repo.addMessage(assistantMsg(conv.id, "A long assistant reply that must never become the title", ts = 2000))

        assertEquals("First question", convDao.conversations[conv.id]?.title)
        assertEquals(2000L, convDao.conversations[conv.id]?.updatedAt)
        assertEquals(1, convDao.updateTitleCount)
        assertEquals(1, convDao.touchCount)
    }

    @Test
    fun `later user messages do not overwrite the title`() = runTest {
        val (repo, convDao, _) = newRepo()
        val conv = repo.createConversation(modelId = null)

        repo.addMessage(userMsg(conv.id, "First question", ts = 1000))
        repo.addMessage(assistantMsg(conv.id, "reply", ts = 2000))
        repo.addMessage(userMsg(conv.id, "Second question", ts = 3000))

        assertEquals("First question", convDao.conversations[conv.id]?.title)
        assertEquals(3000L, convDao.conversations[conv.id]?.updatedAt)
        assertEquals(1, convDao.updateTitleCount)
        assertEquals(2, convDao.touchCount)
    }

    @Test
    fun `long first user message is truncated for the title`() = runTest {
        val (repo, convDao, _) = newRepo()
        val conv = repo.createConversation(modelId = null)
        val long = "x".repeat(100)

        repo.addMessage(userMsg(conv.id, long, ts = 1000))

        val title = convDao.conversations[conv.id]?.title!!
        assertEquals(60, title.length)
        assertEquals("x".repeat(57) + "...", title)
    }

    @Test
    fun `toDomain falls back to USER role on invalid stored role`() = runTest {
        val (repo, _, msgDao) = newRepo()
        msgDao.messages.add(
            MessageEntity(
                id = "m1", conversationId = "c1", role = "GARBAGE_ROLE",
                content = "hi", thinkingContent = null, imageUrisJson = null,
                timestampMs = 1L, tokenCount = null, tokensPerSec = null, parentId = null
            )
        )

        val result = repo.getMessages("c1")

        assertEquals(1, result.size)
        assertEquals(MessageRole.USER, result.first().role)
    }
}
