package com.tryptz.neuron.data.local.dao

import androidx.room.*
import com.tryptz.neuron.data.local.entity.InstalledModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface InstalledModelDao {
    @Query("SELECT * FROM installed_models ORDER BY installedAt DESC")
    fun observeAll(): Flow<List<InstalledModelEntity>>

    @Query("SELECT * FROM installed_models WHERE descriptorId = :descriptorId")
    suspend fun getByDescriptorId(descriptorId: String): InstalledModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(model: InstalledModelEntity)

    @Query("DELETE FROM installed_models WHERE id = :id")
    suspend fun deleteById(id: String)
}
