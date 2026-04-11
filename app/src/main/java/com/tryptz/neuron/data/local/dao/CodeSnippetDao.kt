package com.tryptz.neuron.data.local.dao

import androidx.room.*
import com.tryptz.neuron.data.local.entity.CodeSnippetEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CodeSnippetDao {
    @Query("SELECT * FROM code_snippets WHERE messageId = :messageId ORDER BY version ASC")
    fun observeByMessage(messageId: String): Flow<List<CodeSnippetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snippet: CodeSnippetEntity)

    @Update
    suspend fun update(snippet: CodeSnippetEntity)

    @Query("SELECT MAX(version) FROM code_snippets WHERE messageId = :messageId AND language = :language")
    suspend fun getLatestVersion(messageId: String, language: String): Int?
}
