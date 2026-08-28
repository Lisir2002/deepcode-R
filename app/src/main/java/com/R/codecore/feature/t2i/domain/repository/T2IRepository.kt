package com.R.codecore.feature.t2i.domain.repository

import com.R.codecore.feature.t2i.data.local.entity.T2IProviderEntity
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.R.codecore.feature.t2i.data.local.entity.T2ITaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * T2I 域门面（v2-full-takeover P2-2）。
 *
 * 目的：把 [com.R.codecore.feature.agent.domain.tool.image.GenerateImageTool] 与
 * [com.R.codecore.feature.t2i.domain.permission.T2IPermissionPolicyEngine] 从 Room DAO
 * 解耦，使业务读源可按 [com.R.codecore.datalayer.DataReadMode] 在 Room / V2 间切换。
 *
 * 语义对齐 Room T2IDatabase 三张表 DAO 全集（T2IProviderDao / T2IProviderModelDao / T2ITaskDao）。
 */
interface T2IRepository {

    // ── Provider ──

    suspend fun getActiveProvider(): T2IProviderEntity?
    suspend fun getEnabledProviders(): List<T2IProviderEntity>
    suspend fun getProviderById(id: String): T2IProviderEntity?
    suspend fun upsertProvider(provider: T2IProviderEntity)
    suspend fun deleteProvider(id: String)
    suspend fun deactivateAllProviders()
    suspend fun activateProvider(id: String)
    suspend fun updateProviderEndpointMode(id: String, mode: String)
    suspend fun updateProviderEncryptedApiKey(id: String, newEncrypted: String)

    // ── Provider Model ──

    suspend fun getModelsForProvider(providerId: String): List<T2IProviderModelEntity>
    suspend fun getModel(providerId: String, modelId: String): T2IProviderModelEntity?
    suspend fun upsertModel(model: T2IProviderModelEntity)

    // ── Task ──

    suspend fun insertTask(task: T2ITaskEntity)
    suspend fun getTaskById(id: String): T2ITaskEntity?
    suspend fun getTaskByMessageId(messageId: String): T2ITaskEntity?
    suspend fun listDanglingTasks(cutoffMs: Long): List<T2ITaskEntity>
    suspend fun markTaskSuccess(id: String, imagePath: String, thumbnailPath: String, completedAtMs: Long)
    suspend fun markTaskFailedOrRetry(
        id: String, finalStatus: String, errorCode: String, errorMessage: String, retryCount: Int, updatedAtMs: Long,
    )
    suspend fun setTaskPermissionDecision(id: String, decision: String, deducted: Int, updatedAtMs: Long)
    suspend fun deleteTask(id: String)
    suspend fun deleteTasksBySession(sessionId: String)

    // ── 权限引擎聚合 ──

    suspend fun sumDeductedTokensSince(dayStartMs: Long): Long
    suspend fun sumDeductedTokensForSession(sessionId: String): Long
    suspend fun countSuccessfulImagesSince(dayStartMs: Long): Long
}
