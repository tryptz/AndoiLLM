package com.tryptz.neuron.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val modelId: String?,
    val presetId: String?,
    val createdAt: Long,
    val updatedAt: Long
)

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("conversationId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val content: String,
    val thinkingContent: String?,
    val imageUrisJson: String?,
    val timestampMs: Long,
    val tokenCount: Int?,
    val tokensPerSec: Float?,
    val parentId: String?
)

@Entity(tableName = "installed_models")
data class InstalledModelEntity(
    @PrimaryKey val id: String,
    val descriptorId: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val installedAt: Long
)

@Entity(
    tableName = "code_snippets",
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class,
        parentColumns = ["id"],
        childColumns = ["messageId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("messageId")]
)
data class CodeSnippetEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val language: String,
    val code: String,
    val stdout: String?,
    val stderr: String?,
    val returnValue: String?,
    val executionTimeMs: Long?,
    val memoryUsedBytes: Long?,
    val errorJson: String?,
    val version: Int = 1,
    val createdAt: Long
)
