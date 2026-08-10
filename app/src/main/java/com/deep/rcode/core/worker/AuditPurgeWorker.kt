package com.deep.rcode.core.worker

import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.workspace.domain.repository.RemoteAuditLogRepository
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 审计日志定期清理 Worker。
 * 执行保留策略：10000 条 / 90 天，先到先截断。
 * 不依赖 WorkManager，由 App 启动时周期调用。
 */
@Singleton
class AuditPurgeWorker @Inject constructor(
    private val auditLogRepo: RemoteAuditLogRepository
) {
    private companion object {
        const val TAG = "AuditPurge"
        const val RETENTION_DAYS = 90L
    }

    /**
     * 执行清理。应在 IO 协程上调用。
     * @return 删除的条数
     */
    suspend fun doWork(): Int {
        val cutoffMs = Instant.now().minusSeconds(RETENTION_DAYS * 86400L).toEpochMilli()
        val deleted = auditLogRepo.purgeBefore(cutoffMs)
        if (deleted > 0) {
            FileLogger.i(TAG, "审计日志清理完成：删除 $deleted 条")
        }
        return deleted
    }
}