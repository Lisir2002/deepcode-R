package com.core.deepcode.feature.t2i.data.repository

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.repository.T2iRepository as V2T2iRepository
import com.core.deepcode.feature.t2i.data.local.entity.T2IProviderEntity
import com.core.deepcode.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.core.deepcode.feature.t2i.data.local.entity.T2ITaskEntity
import com.core.deepcode.feature.t2i.domain.repository.T2IRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * T2I 门面 V2 实现（v2-full-takeover P2-2：t2i 域切 V2 读源）。
 *
 * 读写都走 V2 SQLDelight 库；映射层负责 snake_case ↔ Room Entity 字段转换。
 * 写路径语义对齐 Room：provider 激活互斥（deactivateAll + activate 单事务）、
 * task 全列 REPLACE。权限引擎的 3 个聚合（日/会话额度、成功图计数）走 V2 同构查询。
 */
@Singleton
class T2IRepositoryV2Impl @Inject constructor(
    private val v2: V2T2iRepository,
) : T2IRepository {

    private companion object {
        const val TAG = "T2IRepoV2"
    }

    // ── Provider ──

    override suspend fun getActiveProvider(): T2IProviderEntity? =
        v2.getActiveT2iProvider()?.toEntity()

    override suspend fun getEnabledProviders(): List<T2IProviderEntity> =
        v2.listT2iProviders().filter { it.is_enabled == 1L }.map { it.toEntity() }

    override suspend fun getProviderById(id: String): T2IProviderEntity? =
        v2.getT2iProvider(id)?.toEntity()

    override suspend fun upsertProvider(provider: T2IProviderEntity) {
        val now = System.currentTimeMillis()
        // 激活互斥：置 active 前先清全部（与 Room 仓储级 invariant 一致），单事务原子。
        if (provider.isActive) v2.deactivateAllT2iProviders()
        v2.upsertT2iProvider(
            id = provider.id,
            name = provider.name,
            type = provider.type,
            baseUrl = provider.baseUrl,
            encryptedApiKey = provider.encryptedApiKey,
            endpointMode = provider.endpointMode,
            isActive = if (provider.isActive) 1L else 0L,
            priority = provider.priority.toLong(),
            isEnabled = if (provider.isEnabled) 1L else 0L,
            extraHeadersJson = provider.extraHeadersJson,
            createdAtMs = if (provider.createdAtMs == 0L) now else provider.createdAtMs,
            updatedAtMs = now,
        )
    }

    override suspend fun deleteProvider(id: String) {
        v2.deleteT2iProvider(id)
    }
    override suspend fun deactivateAllProviders() {
        v2.deactivateAllT2iProviders()
    }
    override suspend fun activateProvider(id: String) {
        v2.setActiveT2iProvider(id)
    }
    override suspend fun updateProviderEndpointMode(id: String, mode: String) {
        FileLogger.d(TAG, "写回 endpointMode provider=$id mode=$mode")
        v2.setT2iProviderEndpointMode(id, mode, System.currentTimeMillis())
    }

    override suspend fun updateProviderEncryptedApiKey(id: String, newEncrypted: String) {
        v2.updateT2iProviderEncryptedApiKey(id, newEncrypted, System.currentTimeMillis())
    }

    // ── Provider Model ──

    override suspend fun getModelsForProvider(providerId: String): List<T2IProviderModelEntity> =
        v2.listT2iModels(providerId).map { it.toEntity() }

    override suspend fun getModel(providerId: String, modelId: String): T2IProviderModelEntity? =
        v2.listT2iModels(providerId).firstOrNull { it.model_id == modelId }?.toEntity()

    override suspend fun upsertModel(model: T2IProviderModelEntity) {
        v2.upsertT2iProviderModel(
            id = model.id,
            providerId = model.providerId,
            modelId = model.modelId,
            displayName = model.displayName,
            supportsHd = if (model.supportsHd) 1L else 0L,
            supportsInpaint = if (model.supportsInpaint) 1L else 0L,
            defaultWidth = model.defaultWidth.toLong(),
            defaultHeight = model.defaultHeight.toLong(),
            maxSteps = model.maxSteps.toLong(),
            defaultSteps = model.defaultSteps.toLong(),
            costPerImageTokens = model.costPerImageTokens.toLong(),
            createdAtMs = model.createdAtMs,
            updatedAtMs = System.currentTimeMillis(),
        )
    }

    // ── Task ──

    override suspend fun insertTask(task: T2ITaskEntity) {
        v2.insertTask(
            id = task.id, sessionId = task.sessionId, messageId = task.messageId,
            prompt = task.prompt, negativePrompt = task.negativePrompt,
            width = task.width.toLong(), height = task.height.toLong(), steps = task.steps.toLong(),
            seed = task.seed.toLong(), hd = if (task.hd) 1L else 0L,
            providerId = task.providerId, modelId = task.modelId,
            providerRef = task.providerRef, endpointModeRef = task.endpointModeRef,
            status = task.status, imagePath = task.imagePath, thumbnailPath = task.thumbnailPath,
            remoteTaskId = task.remoteTaskId, progressPercent = task.progressPercent.toLong(),
            retryCount = task.retryCount.toLong(), maxRetries = task.maxRetries.toLong(),
            errorCode = task.errorCode, errorMessage = task.errorMessage,
            permissionDecision = task.permissionDecision, quotaDeductedTokens = task.quotaDeductedTokens.toLong(),
            createdAtMs = task.createdAtMs, updatedAtMs = task.updatedAtMs, completedAtMs = task.completedAtMs,
        )
    }

    override suspend fun getTaskById(id: String): T2ITaskEntity? = v2.getTask(id)?.toEntity()
    override suspend fun getTaskByMessageId(messageId: String): T2ITaskEntity? = v2.getTaskByMessageId(messageId)?.toEntity()
    override suspend fun listDanglingTasks(cutoffMs: Long): List<T2ITaskEntity> = v2.listDanglingTasks(cutoffMs).map { it.toEntity() }
    override suspend fun markTaskSuccess(id: String, imagePath: String, thumbnailPath: String, completedAtMs: Long) {
        v2.markTaskSuccess(id, imagePath, thumbnailPath, completedAtMs)
    }
    override suspend fun markTaskFailedOrRetry(
        id: String, finalStatus: String, errorCode: String, errorMessage: String, retryCount: Int, updatedAtMs: Long,
    ) {
        v2.markTaskFailedOrRetry(id, finalStatus, errorCode, errorMessage, retryCount.toLong(), updatedAtMs)
    }
    override suspend fun setTaskPermissionDecision(id: String, decision: String, deducted: Int, updatedAtMs: Long) {
        v2.setTaskPermissionDecision(id, decision, deducted.toLong(), updatedAtMs)
    }
    override suspend fun deleteTask(id: String) {
        v2.deleteTask(id)
    }
    override suspend fun deleteTasksBySession(sessionId: String) {
        v2.deleteTasksBySession(sessionId)
    }

    // ── 权限引擎聚合 ──

    override suspend fun sumDeductedTokensSince(dayStartMs: Long): Long = v2.sumDeductedTokensSince(dayStartMs)
    override suspend fun sumDeductedTokensForSession(sessionId: String): Long = v2.sumDeductedTokensForSession(sessionId)
    override suspend fun countSuccessfulImagesSince(dayStartMs: Long): Long = v2.countSuccessfulImagesSince(dayStartMs)

    // ── 映射 ──

    private fun com.core.deepcode.datalayer.sqldelight.t2i.T2i_providers.toEntity() = T2IProviderEntity(
        id = id,
        name = name,
        type = type,
        baseUrl = base_url,
        encryptedApiKey = encrypted_api_key,
        endpointMode = endpoint_mode,
        isActive = is_active == 1L,
        priority = priority.toInt(),
        isEnabled = is_enabled == 1L,
        extraHeadersJson = extra_headers_json,
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms,
    )

    private fun com.core.deepcode.datalayer.sqldelight.t2i.T2i_provider_models.toEntity() = T2IProviderModelEntity(
        id = id,
        providerId = provider_id,
        modelId = model_id,
        displayName = display_name,
        supportsHd = supports_hd == 1L,
        supportsInpaint = supports_inpaint == 1L,
        defaultWidth = default_width.toInt(),
        defaultHeight = default_height.toInt(),
        maxSteps = max_steps.toInt(),
        defaultSteps = default_steps.toInt(),
        costPerImageTokens = cost_per_image_tokens.toInt(),
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms,
    )

    private fun com.core.deepcode.datalayer.sqldelight.t2i.T2i_task.toEntity() = T2ITaskEntity(
        id = id,
        sessionId = session_id,
        messageId = message_id,
        prompt = prompt,
        negativePrompt = negative_prompt,
        width = width.toInt(),
        height = height.toInt(),
        steps = steps.toInt(),
        seed = seed.toInt(),
        hd = hd == 1L,
        providerId = provider_id,
        modelId = model_id,
        providerRef = provider_ref,
        endpointModeRef = endpoint_mode_ref,
        status = status,
        imagePath = image_path,
        thumbnailPath = thumbnail_path,
        remoteTaskId = remote_task_id,
        progressPercent = progress_percent.toInt(),
        retryCount = retry_count.toInt(),
        maxRetries = max_retries.toInt(),
        errorCode = error_code,
        errorMessage = error_message,
        permissionDecision = permission_decision,
        quotaDeductedTokens = quota_deducted_tokens.toInt(),
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms,
        completedAtMs = completed_at_ms,
    )
}
