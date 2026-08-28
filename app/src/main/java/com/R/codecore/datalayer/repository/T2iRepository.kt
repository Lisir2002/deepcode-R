package com.R.codecore.datalayer.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.R.codecore.datalayer.sqldelight.T2iDb
import com.R.codecore.datalayer.sqldelight.t2i.T2i_result
import com.R.codecore.datalayer.sqldelight.t2i.T2i_task
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * t2i 域 Repository（设计 §11.5 / L2）：文生图任务 + 多结果门面。
 * 结果图字节存 BlobStore，本域只留 blob_ref 引用。
 *
 * v2-full-takeover P0-1：补 Flow 响应式读，对齐 Room DAO 的 4 个 Flow 查询。
 */
class T2iRepository(private val db: T2iDb) {

    private val q get() = db.t2iQueries

    // ── 任务（t2i_task，P2-2 对齐 Room T2ITaskDao 全集）──

    suspend fun insertTask(
        id: String, sessionId: String, messageId: String, prompt: String, negativePrompt: String,
        width: Long, height: Long, steps: Long, seed: Long, hd: Long,
        providerId: String, modelId: String, providerRef: String, endpointModeRef: String,
        status: String, imagePath: String, thumbnailPath: String, remoteTaskId: String,
        progressPercent: Long, retryCount: Long, maxRetries: Long,
        errorCode: String, errorMessage: String, permissionDecision: String, quotaDeductedTokens: Long,
        createdAtMs: Long, updatedAtMs: Long, completedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertTask(id, sessionId, messageId, prompt, negativePrompt, width, height, steps, seed, hd,
            providerId, modelId, providerRef, endpointModeRef, status, imagePath, thumbnailPath, remoteTaskId,
            progressPercent, retryCount, maxRetries, errorCode, errorMessage, permissionDecision, quotaDeductedTokens,
            createdAtMs, updatedAtMs, completedAtMs)
    }

    suspend fun upsertTask(
        id: String, sessionId: String, messageId: String, prompt: String, negativePrompt: String,
        width: Long, height: Long, steps: Long, seed: Long, hd: Long,
        providerId: String, modelId: String, providerRef: String, endpointModeRef: String,
        status: String, imagePath: String, thumbnailPath: String, remoteTaskId: String,
        progressPercent: Long, retryCount: Long, maxRetries: Long,
        errorCode: String, errorMessage: String, permissionDecision: String, quotaDeductedTokens: Long,
        createdAtMs: Long, updatedAtMs: Long, completedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertOrReplaceTask(id, sessionId, messageId, prompt, negativePrompt, width, height, steps, seed, hd,
            providerId, modelId, providerRef, endpointModeRef, status, imagePath, thumbnailPath, remoteTaskId,
            progressPercent, retryCount, maxRetries, errorCode, errorMessage, permissionDecision, quotaDeductedTokens,
            createdAtMs, updatedAtMs, completedAtMs)
    }

    suspend fun getTask(id: String): T2i_task? =
        withContext(Dispatchers.IO) { q.selectTaskById(id).executeAsOneOrNull() }

    suspend fun getTaskByMessageId(messageId: String): T2i_task? =
        withContext(Dispatchers.IO) { q.selectTaskByMessageId(messageId).executeAsOneOrNull() }

    suspend fun listTasks(): List<T2i_task> =
        withContext(Dispatchers.IO) { q.selectAllTasks().executeAsList() }

    suspend fun listTasksBySession(sessionId: String): List<T2i_task> =
        withContext(Dispatchers.IO) { q.selectTasksBySession(sessionId).executeAsList() }

    suspend fun listDanglingTasks(cutoffMs: Long): List<T2i_task> =
        withContext(Dispatchers.IO) { q.selectDanglingTasks(cutoffMs).executeAsList() }

    suspend fun updateTaskStatus(id: String, status: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.updateTaskStatus(status, now, id) }

    suspend fun updateTaskStatusAndProgress(id: String, status: String, progress: Long, now: Long) =
        withContext(Dispatchers.IO) { q.updateTaskStatusAndProgress(status, progress, now, id) }

    suspend fun markTaskSuccess(id: String, imagePath: String, thumbnailPath: String, now: Long) =
        withContext(Dispatchers.IO) { q.markTaskSuccess(imagePath, thumbnailPath, now, now, id) }

    suspend fun markTaskFailedOrRetry(
        id: String, finalStatus: String, errorCode: String, errorMessage: String, retryCount: Long, now: Long,
    ) = withContext(Dispatchers.IO) {
        q.markTaskFailedOrRetry(finalStatus, errorCode, errorMessage, retryCount, now, id)
    }

    suspend fun setTaskRemoteId(id: String, remoteTaskId: String, now: Long) =
        withContext(Dispatchers.IO) { q.setTaskRemoteId(remoteTaskId, now, id) }

    suspend fun setTaskPermissionDecision(id: String, decision: String, deducted: Long, now: Long) =
        withContext(Dispatchers.IO) { q.setTaskPermissionDecision(decision, deducted, now, id) }

    suspend fun deleteTask(id: String) =
        withContext(Dispatchers.IO) { q.deleteTask(id) }

    suspend fun deleteTasksBySession(sessionId: String) =
        withContext(Dispatchers.IO) { q.deleteTasksBySession(sessionId) }

    suspend fun sumDeductedTokensSince(dayStartMs: Long): Long =
        withContext(Dispatchers.IO) { q.sumDeductedTokensSince(dayStartMs).executeAsOne() }

    suspend fun sumDeductedTokensForSession(sessionId: String): Long =
        withContext(Dispatchers.IO) { q.sumDeductedTokensForSession(sessionId).executeAsOne() }

    suspend fun countSuccessfulImagesSince(dayStartMs: Long): Long =
        withContext(Dispatchers.IO) { q.countSuccessfulImagesSince(dayStartMs).executeAsOne() }

    // ── 结果（t2i_result）──

    suspend fun addResult(
        id: String, taskId: String, blobRef: String, seed: Long?, width: Long?, height: Long?, seq: Long,
    ) = withContext(Dispatchers.IO) { q.insertResult(id, taskId, blobRef, seed, width, height, seq) }

    suspend fun listResults(taskId: String): List<T2i_result> =
        withContext(Dispatchers.IO) { q.selectResultsByTask(taskId).executeAsList() }

    // ── 阶段 1 补表方法（t2i_providers / t2i_provider_models）──

    suspend fun insertT2iProvider(
        id: String, name: String, type: String, baseUrl: String, encryptedApiKey: String,
        endpointMode: String, isActive: Long, priority: Long, isEnabled: Long,
        extraHeadersJson: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertT2iProvider(id, name, type, baseUrl, encryptedApiKey, endpointMode, isActive, priority, isEnabled, extraHeadersJson, createdAtMs, updatedAtMs)
    }

    suspend fun upsertT2iProvider(
        id: String, name: String, type: String, baseUrl: String, encryptedApiKey: String,
        endpointMode: String, isActive: Long, priority: Long, isEnabled: Long,
        extraHeadersJson: String, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertOrReplaceT2iProvider(id, name, type, baseUrl, encryptedApiKey, endpointMode, isActive, priority, isEnabled, extraHeadersJson, createdAtMs, updatedAtMs)
    }

    suspend fun getT2iProvider(id: String): com.R.codecore.datalayer.sqldelight.t2i.T2i_providers? =
        withContext(Dispatchers.IO) { q.selectT2iProviderById(id).executeAsOneOrNull() }

    suspend fun listT2iProviders(): List<com.R.codecore.datalayer.sqldelight.t2i.T2i_providers> =
        withContext(Dispatchers.IO) { q.selectAllT2iProviders().executeAsList() }

    suspend fun getActiveT2iProvider(): com.R.codecore.datalayer.sqldelight.t2i.T2i_providers? =
        withContext(Dispatchers.IO) { q.selectActiveT2iProvider().executeAsOneOrNull() }

    suspend fun deactivateAllT2iProviders() =
        withContext(Dispatchers.IO) { q.deactivateAllT2iProviders() }

    suspend fun setActiveT2iProvider(id: String) =
        withContext(Dispatchers.IO) { q.setT2iProviderActive(id) }

    suspend fun setT2iProviderEnabled(id: String, isEnabled: Boolean, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.setT2iProviderEnabled(if (isEnabled) 1L else 0L, updatedAtMs, id) }

    suspend fun setT2iProviderEndpointMode(id: String, mode: String, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.setT2iProviderEndpointMode(mode, updatedAtMs, id) }

    suspend fun updateT2iProviderEncryptedApiKey(id: String, encryptedApiKey: String, updatedAtMs: Long) =
        withContext(Dispatchers.IO) { q.updateT2iProviderEncryptedApiKey(encryptedApiKey, updatedAtMs, id) }

    suspend fun deleteT2iProvider(id: String) =
        withContext(Dispatchers.IO) { q.deleteT2iProvider(id) }

    suspend fun insertT2iProviderModel(
        id: String, providerId: String, modelId: String, displayName: String, supportsHd: Long,
        supportsInpaint: Long, defaultWidth: Long, defaultHeight: Long, maxSteps: Long,
        defaultSteps: Long, costPerImageTokens: Long, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertT2iProviderModel(id, providerId, modelId, displayName, supportsHd, supportsInpaint, defaultWidth, defaultHeight, maxSteps, defaultSteps, costPerImageTokens, createdAtMs, updatedAtMs)
    }

    suspend fun upsertT2iProviderModel(
        id: String, providerId: String, modelId: String, displayName: String, supportsHd: Long,
        supportsInpaint: Long, defaultWidth: Long, defaultHeight: Long, maxSteps: Long,
        defaultSteps: Long, costPerImageTokens: Long, createdAtMs: Long, updatedAtMs: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertOrReplaceT2iProviderModel(id, providerId, modelId, displayName, supportsHd, supportsInpaint, defaultWidth, defaultHeight, maxSteps, defaultSteps, costPerImageTokens, createdAtMs, updatedAtMs)
    }

    suspend fun listT2iModels(providerId: String): List<com.R.codecore.datalayer.sqldelight.t2i.T2i_provider_models> =
        withContext(Dispatchers.IO) { q.selectT2iModelsByProvider(providerId).executeAsList() }

    suspend fun deleteT2iProviderModels(providerId: String) =
        withContext(Dispatchers.IO) { q.deleteT2iProviderModels(providerId) }

    // ── P0-1 Flow 响应式读（对齐 Room DAO 的 4 个 Flow 查询）──

    fun observeAllTasks(): Flow<List<T2i_task>> =
        q.selectAllTasks().asFlow().mapToList(Dispatchers.IO)

    fun observeResultsByTask(taskId: String): Flow<List<T2i_result>> =
        q.selectResultsByTask(taskId).asFlow().mapToList(Dispatchers.IO)

    fun observeAllT2iProviders(): Flow<List<com.R.codecore.datalayer.sqldelight.t2i.T2i_providers>> =
        q.selectAllT2iProviders().asFlow().mapToList(Dispatchers.IO)

    fun observeT2iModelsByProvider(providerId: String): Flow<List<com.R.codecore.datalayer.sqldelight.t2i.T2i_provider_models>> =
        q.selectT2iModelsByProvider(providerId).asFlow().mapToList(Dispatchers.IO)
}
