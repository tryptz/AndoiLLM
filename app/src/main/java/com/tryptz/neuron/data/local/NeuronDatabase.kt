package com.tryptz.neuron.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.tryptz.neuron.data.local.dao.*
import com.tryptz.neuron.data.local.entity.*

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        InstalledModelEntity::class,
        LocalModelEntity::class,
        CodeSnippetEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class NeuronDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun installedModelDao(): InstalledModelDao
    abstract fun localModelDao(): LocalModelDao
    abstract fun codeSnippetDao(): CodeSnippetDao
}
