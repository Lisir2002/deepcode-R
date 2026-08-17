package com.R.codecore.feature.workspace.domain.repository

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.workspace.data.local.dao.RemoteAuditLogDao
import com.R.codecore.feature.workspace.data.local.entity.RemoteAuditLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@Singleton
class RemoteAuditLogRepository @Inject constructor(
    private val dao: RemoteAuditLogDao
) {
    private companion object {
        const val TAG = "RemoteAuditLogRepo"
        const val MAX_MESSAGE_LENGTH = 500
        const val RETENTION_MAX_COUNT = 10_000L
        const val RETENTION_DAYS = 90L
    }

    /**
     * 主写入口：截断 message ≤500 字符，取当前时间。
     * 所有 audit 写入都走此方法，确保格式统一。
     */
    suspend fun append(
        category: String,
        action: String,
        success: Boolean,
        message: String? = null,
        connectionId: String? = null,
        connectionName: String? = null,
        remoteHost: String? = null,
        sourceIp: String? = null,
        createdAtMs: Long = Instant.now().toEpochMilli()
    ): Long = withContext(Dispatchers.IO) {
        val truncated = message?.take(MAX_MESSAGE_LENGTH)
        try {
            val id = dao.insert(
                RemoteAuditLogEntity(
                    category = category,
                    action = action,
                    connectionId = connectionId,
                    connectionName = connectionName,
                    remoteHost = remoteHost,
                    success = success,
                    message = truncated,
                    sourceIp = sourceIp,
                    createdAt = createdAtMs
                )
            )
            if (id % 50L == 0L) {
                // 每 50 条检查一次保留上限
                enforceRetentionIfNeeded()
            }
            id
        } catch (e: Exception) {
            FileLogger.w(TAG, "写入审计日志失败", e)
            -1L
        }
    }

    suspend fun pageDesc(page: Int, pageSize: Int = 50): List<RemoteAuditLogEntity> =
        withContext(Dispatchers.IO) {
            dao.pageDesc(offset = page * pageSize, limit = pageSize)
        }

    suspend fun listByConnection(connectionId: String, limit: Int = 200): List<RemoteAuditLogEntity> =
        withContext(Dispatchers.IO) {
            dao.listByConnection(connectionId, limit)
        }

    suspend fun filter(
        categories: List<String> = emptyList(),
        onlyFailures: Boolean = false,
        sinceMs: Long = 0L,
        limit: Int = 500
    ): List<RemoteAuditLogEntity> = withContext(Dispatchers.IO) {
        val cats = if (categories.isEmpty()) {
            dao.listCategories().ifEmpty { listOf("") }
        } else {
            categories
        }
        val successes = if (onlyFailures) listOf(false) else listOf(true, false)
        dao.filter(cats = cats, successes = successes, sinceMs = sinceMs, limit = limit)
    }

    /**
     * 清理保留期外的日志。
     * @return 实际删除条数。
     */
    suspend fun purgeBefore(dateMs: Long): Int = withContext(Dispatchers.IO) {
        dao.purgeBefore(dateMs)
    }

    /**
     * 导出为 JSON 字符串（脱敏版本，不含 password/passphrase 等敏感字段）。
     */
    suspend fun exportToJson(
        filterCategories: List<String>? = null,
        sinceMs: Long = 0L
    ): String = withContext(Dispatchers.IO) {
        val logs = if (filterCategories != null) {
            filter(categories = filterCategories, sinceMs = sinceMs, limit = 5000)
        } else {
            dao.pageDesc(offset = 0, limit = 5000)
        }
        val json = Json { prettyPrint = true }
        json.encodeToString(logs.map { log ->
            mapOf(
                "id" to log.id,
                "category" to log.category,
                "action" to log.action,
                "connectionId" to log.connectionId,
                "connectionName" to log.connectionName,
                "remoteHost" to log.remoteHost,
                "success" to log.success,
                "message" to log.message,
                "createdAt" to log.createdAt
            )
        })
    }

    /** 获取当前日志总数。 */
    suspend fun count(): Long = withContext(Dispatchers.IO) { dao.count() }

    // ============== 保留策略 ==============

    /**
     * 检查是否达到保留上限，是则截断。
     * 触发条件：10000 条 或 90 天，先到先截断。
     */
    suspend fun enforceRetentionIfNeeded() {
        val currentCount = dao.count()
        if (currentCount > RETENTION_MAX_COUNT) {
            val cutoffMs = Instant.now().minusSeconds(RETENTION_DAYS * 86400L).toEpochMilli()
            val deleted = dao.purgeBefore(cutoffMs)
            FileLogger.i(TAG, "审计日志保留清理：删除 $deleted 条（保留 ${RETENTION_MAX_COUNT} 条 / ${RETENTION_DAYS} 天）")
        }
    }
}