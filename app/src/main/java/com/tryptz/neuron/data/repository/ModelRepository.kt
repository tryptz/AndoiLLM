package com.tryptz.neuron.data.repository

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import com.tryptz.neuron.data.local.dao.InstalledModelDao
import com.tryptz.neuron.data.local.dao.LocalModelDao
import com.tryptz.neuron.data.local.entity.InstalledModelEntity
import com.tryptz.neuron.data.local.entity.LocalModelEntity
import com.tryptz.neuron.data.model.ModelRegistry
import com.tryptz.neuron.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedModelDao: InstalledModelDao,
    private val localModelDao: LocalModelDao
) {
    val modelsDir: File = File(context.filesDir, "models").also { it.mkdirs() }

    fun getAllDescriptors(): List<ModelDescriptor> = ModelRegistry.models
    fun getRecommended(): List<ModelDescriptor> = ModelRegistry.getRecommended()
    fun getDescriptorById(id: String): ModelDescriptor? = ModelRegistry.getByRawId(id)

    fun observeInstalled(): Flow<List<ModelDescriptor>> =
        installedModelDao.observeAll().map { entities ->
            entities.mapNotNull { entity ->
                ModelRegistry.getByRawId(entity.descriptorId)
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

    // ── Local model import ──

    fun observeLocalModels(): Flow<List<LocalModelEntity>> =
        localModelDao.observeAll()

    suspend fun getLocalModel(id: String): LocalModelEntity? =
        localModelDao.getById(id)

    suspend fun importLocalModel(
        uri: Uri,
        name: String,
        chatTemplate: ChatTemplate,
        contextLength: Int,
        architecture: String? = null,
        quantization: String? = null,
        parameterCount: Long? = null
    ): Result<LocalModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = resolveFileName(uri)
            val destFile = File(modelsDir, fileName)

            context.contentResolver.openInputStream(uri)?.use { input ->
                destFile.outputStream().use { output -> input.copyTo(output) }
            } ?: throw IllegalStateException("Cannot read file")

            val entity = LocalModelEntity(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { fileName.removeSuffix(".gguf") },
                fileName = fileName,
                filePath = destFile.absolutePath,
                fileSizeBytes = destFile.length(),
                chatTemplate = chatTemplate.raw,
                contextLength = contextLength,
                architecture = architecture,
                quantization = quantization,
                parameterCount = parameterCount,
                installedAt = System.currentTimeMillis()
            )
            localModelDao.insert(entity)
            entity
        }
    }

    suspend fun deleteLocalModel(id: String) {
        val entity = localModelDao.getById(id) ?: return
        File(entity.filePath).delete()
        localModelDao.deleteById(id)
    }

    fun buildLocalDescriptor(entity: LocalModelEntity): ModelDescriptor {
        val quant = entity.quantization?.let { q ->
            Quantization.entries.find { it.label.equals(q, ignoreCase = true) }
        } ?: Quantization.Q4_K_M
        val fileSizeMb = (entity.fileSizeBytes / (1024 * 1024)).toInt()
        val paramsStr = entity.parameterCount?.let { formatParamCount(it) } ?: "Unknown"

        return ModelDescriptor(
            modelId = ModelId.LOCAL,
            name = entity.name,
            family = entity.architecture ?: "local",
            totalParams = paramsStr,
            quantization = quant,
            fileSizeMb = fileSizeMb,
            ramRequiredMb = fileSizeMb + 500,
            maxContext = entity.contextLength,
            supportedBackends = listOf(InferenceBackend.CPU),
            chatTemplate = ChatTemplate.fromRaw(entity.chatTemplate),
            huggingFaceRepo = "",
            huggingFaceFile = "",
            localId = entity.id
        )
    }

    private fun formatParamCount(params: Long): String = when {
        params >= 1_000_000_000 -> "%.1fB".format(params / 1_000_000_000.0)
        params >= 1_000_000 -> "%.0fM".format(params / 1_000_000.0)
        else -> "${params}"
    }

    private fun resolveFileName(uri: Uri): String {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        val nameFromUri = cursor?.use {
            val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && nameIndex >= 0) it.getString(nameIndex) else null
        }
        return nameFromUri ?: "model_${System.currentTimeMillis()}.gguf"
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
