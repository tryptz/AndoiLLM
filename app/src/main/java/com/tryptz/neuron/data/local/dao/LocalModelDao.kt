package com.tryptz.neuron.data.local.dao

import androidx.room.*
import com.tryptz.neuron.data.local.entity.LocalModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface LocalModelDao {
    @Query("SELECT * FROM local_models ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<LocalModelEntity>>

    @Query("SELECT * FROM local_models WHERE id = :id")
    suspend fun getById(id: String): LocalModelEntity?

    @Query("SELECT * FROM local_models WHERE filePath = :filePath LIMIT 1")
    suspend fun getByFilePath(filePath: String): LocalModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: LocalModelEntity)

    @Query("DELETE FROM local_models WHERE id = :id")
    suspend fun deleteById(id: String)
}
