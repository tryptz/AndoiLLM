package com.tryptz.neuron.data.repository

import android.app.ActivityManager
import android.content.Context
import com.tryptz.neuron.data.local.dao.InstalledModelDao
import com.tryptz.neuron.data.local.entity.InstalledModelEntity
import com.tryptz.neuron.data.model.ModelRegistry
import com.tryptz.neuron.domain.model.ModelDescriptor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedModelDao: InstalledModelDao
) {
    val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }

    fun getAllDescriptors(): List<ModelDescriptor> = ModelRegistry.models
    fun getRecommended(): List<ModelDescriptor> = ModelRegistry.getRecommended()
    fun getDescriptorById(id: String): ModelDescriptor? = ModelRegistry.getById(id)

    fun observeInstalled(): Flow<List<ModelDescriptor>> =
        installedModelDao.observeAll().map { entities ->
            entities.mapNotNull { entity ->
                ModelRegistry.getById(entity.descriptorId)
            }
        }

    suspend fun isInstalled(descriptorId: String): Boolean =
        installedModelDao.getByDescriptorId(descriptorId) != null

    suspend fun getModelPath(descriptorId: String): String? =
        installedModelDao.getByDescriptorId(descriptorId)?.filePath

    suspend fun registerInstalled(descriptorId: String, filePath: String, sizeBytes: Long) {
        installedModelDao.insert(
            InstalledModelEntity(
                id = descriptorId,
                descriptorId = descriptorId,
                filePath = filePath,
                fileSizeBytes = sizeBytes,
                installedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun deleteModel(descriptorId: String) {
        val entity = installedModelDao.getByDescriptorId(descriptorId) ?: return
        File(entity.filePath).delete()
        installedModelDao.deleteById(entity.id)
    }

    fun getAvailableRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.availMem / (1024 * 1024)).toInt()
    }

    fun getTotalRamMb(): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return (memInfo.totalMem / (1024 * 1024)).toInt()
    }
}
