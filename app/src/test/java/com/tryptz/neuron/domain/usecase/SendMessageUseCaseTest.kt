package com.tryptz.neuron.domain.usecase

import com.tryptz.neuron.data.repository.ConversationRepository
import com.tryptz.neuron.domain.model.ChatMessage
import com.tryptz.neuron.domain.model.Conversation
import com.tryptz.neuron.domain.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the stale-history fix (task 3): the use case returns the full,
 * up-to-date message list including the just-persisted user message.
 */
class SendMessageUseCaseTest {

    private class FakeConversationRepository : ConversationRepository {
        val conversations = mutableMapOf<String, Conversation>()
        val messages = mutableListOf<ChatMessage>()

        override fun observeConversations(): Flow<List<Conversation>> = flowOf(conversations.values.toList())
        override fun observeMessages(conversationId: String): Flow<List<ChatMessage>> =
            flowOf(messages.filter { it.conversationId == conversationId })

        override suspend fun createConversation(modelId: String?, presetId: String?): Conversation {
            val conv = Conversation(
                id = UUID.randomUUID().toString(),
                title = "New Chat",
                modelId = modelId,
                createdAt = 0L,
                updatedAt = 0L,
                presetId = presetId
            )
            conversations[conv.id] = conv
            return conv
        }

        override suspend fun addMessage(message: ChatMessage) {
            messages.add(message)
        }

        override suspend fun getMessages(conversationId: String): List<ChatMessage> =
            messages.filter { it.conversationId == conversationId }.sortedBy { it.timestampMs }

        override suspend fun updateMessage(message: ChatMessage) {}
        override suspend fun deleteConversation(id: String) { conversations.remove(id) }
        override suspend fun deleteMessagesAfter(conversationId: String, timestampMs: Long) {
            messages.removeAll { it.conversationId == conversationId && it.timestampMs > timestampMs }
        }
    }

    @Test
    fun `creates conversation and returns list containing the user message`() = runTest {
        val repo = FakeConversationRepository()
        val useCase = SendMessageUseCase(repo)

        val (convId, messages) = useCase("Hello", conversationId = null, modelId = "m1")

        assertTrue(repo.conversations.containsKey(convId))
        assertEquals(1, messages.size)
        assertEquals(MessageRole.USER, messages.first().role)
        assertEquals("Hello", messages.first().content)
    }

    @Test
    fun `returned list includes prior history plus the new message - no stale read`() = runTest {
        val repo = FakeConversationRepository()
        val conv = repo.createConversation(modelId = "m1")
        repo.addMessage(ChatMessage(id = "1", conversationId = conv.id, role = MessageRole.USER, content = "Q1", timestampMs = 100))
        repo.addMessage(ChatMessage(id = "2", conversationId = conv.id, role = MessageRole.ASSISTANT, content = "A1", timestampMs = 200))

        val useCase = SendMessageUseCase(repo)
        val (convId, messages) = useCase("Q2", conversationId = conv.id, modelId = "m1")

        assertEquals(conv.id, convId)
        // The returned list must contain all 3 turns so generation isn't run on stale history.
        assertEquals(3, messages.size)
        assertEquals(listOf("Q1", "A1", "Q2"), messages.map { it.content })
    }
}
