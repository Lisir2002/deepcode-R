package com.R.codecore.feature.t2i.data.repository

import com.R.codecore.feature.t2i.data.local.dao.T2IProviderDao
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderModelDao
import com.R.codecore.feature.t2i.data.local.dao.T2ITaskDao
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderEntity
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.R.codecore.feature.t2i.data.local.entity.T2ITaskEntity
import com.R.codecore.feature.t2i.domain.repository.T2IRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2I 门面 Room 实现（v2-full-takeover P2-2，回退路径）。
 * 直接委托 3 个 Room DAO，行为与旧代码完全一致。
 */
@Singleton
class T2IRepositoryRoomImpl @Inject constructor(
    private val providerDao: T2IProviderDao,
    private val modelDao: T2IProviderModelDao,
    private val taskDao: T2ITaskDao,
) : T2IRepository {

    override suspend fun getActiveProvider(): T2IProviderEntity? = providerDao.getActiveProviderSync()
    override suspend fun getEnabledProviders(): List<T2IProviderEntity> = providerDao.getEnabledProvidersOnce()
    override suspend fun getProviderById(id: String): T2IProviderEntity? = providerDao.getProviderById(id)
    override suspend fun upsertProvider(provider: T2IProviderEntity) = providerDao.insertProvider(provider)
    override suspend fun deleteProvider(id: String) = providerDao.deleteProvider(id)
    override suspend fun deactivateAllProviders() = providerDao.deactivateAllProviders()
    override suspend fun activateProvider(id: String) = providerDao.activateProvider(id)
    override suspend fun updateProviderEndpointMode(id: String, mode: String) = providerDao.updateEndpointMode(id, mode)
    override suspend fun updateProviderEncryptedApiKey(id: String, newEncrypted: String) = providerDao.updateEncryptedApiKey(id, newEncrypted)

    override suspend fun getModelsForProvider(providerId: String): List<T2IProviderModelEntity> = modelDao.getModelsForProviderOnce(providerId)
    override suspend fun getModel(providerId: String, modelId: String): T2IProviderModelEntity? = modelDao.getModel(providerId, modelId)
    override suspend fun upsertModel(model: T2IProviderModelEntity) = modelDao.insertModel(model)

    override suspend fun insertTask(task: T2ITaskEntity) = taskDao.insertTask(task)
    override suspend fun getTaskById(id: String): T2ITaskEntity? = taskDao.getTaskById(id)
    override suspend fun getTaskByMessageId(messageId: String): T2ITaskEntity? = taskDao.getTaskByMessageId(messageId)
    override suspend fun listDanglingTasks(cutoffMs: Long): List<T2ITaskEntity> = taskDao.getDanglingTasks(cutoffMs)
    override suspend fun markTaskSuccess(id: String, imagePath: String, thumbnailPath: String, completedAtMs: Long) =
        taskDao.markSuccess(id, imagePath, thumbnailPath, completedAtMs)
    override suspend fun markTaskFailedOrRetry(
        id: String, finalStatus: String, errorCode: String, errorMessage: String, retryCount: Int, updatedAtMs: Long,
    ) = taskDao.markFailedOrRetry(id, finalStatus, errorCode, errorMessage, retryCount, updatedAtMs)
    override suspend fun setTaskPermissionDecision(id: String, decision: String, deducted: Int, updatedAtMs: Long) =
        taskDao.setPermissionDecision(id, decision, deducted, updatedAtMs)
    override suspend fun deleteTask(id: String) = taskDao.deleteTask(id)
    override suspend fun deleteTasksBySession(sessionId: String) = taskDao.deleteTasksForSession(sessionId)

    override suspend fun sumDeductedTokensSince(dayStartMs: Long): Long = taskDao.sumDeductedTokensSince(dayStartMs).toLong()
    override suspend fun sumDeductedTokensForSession(sessionId: String): Long = taskDao.sumDeductedTokensForSession(sessionId).toLong()
    override suspend fun countSuccessfulImagesSince(dayStartMs: Long): Long = taskDao.countSuccessfulImagesSince(dayStartMs).toLong()
}
