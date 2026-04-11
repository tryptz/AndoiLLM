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
        CodeSnippetEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class NeuronDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun installedModelDao(): InstalledModelDao
    abstract fun codeSnippetDao(): CodeSnippetDao
}
