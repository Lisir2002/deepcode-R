package com.R.codecore.feature.agent.data.repository

import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.feature.agent.data.local.entity.CheckpointEntity
import com.R.codecore.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.12 Context Checkpoint Repository（四方联动第 4 方；薄封装 DAO）。
 *
 *
 * 职责：
 *   prePlan：为「AI 即将写代码」创建 checkpoint（含所有要修改文件的 hash 快照）
 *   Postflight 失败：按 checkpointId 还原文件（Phase 5 onFailure 调）
 *   CrashRecovery：启动时扫未过期 checkpoint（C.4.3）
 *
 * 不变性：
 *   CKPT-INV-1：同一 userMessageId 只允许一个 checkpoint（幂等；重复调返回原有 id）
 *   CKPT-INV-2：snapshot 存相对路径 + hash，绝不存文件内容（避免 DB 膨胀 + 隐私）
 *   CKPT-INV-3：deleteCheckpointsBefore 只能删 createdAt < cutoff，绝不删会话内最近一条
 */
@Singleton
class ZthCheckpointRepository @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {

    /** prePlan：新建 checkpoint（若该 messageId 已有则复用）。 */
    suspend fun createOrGet(
        checkpointId: String,
        sessionId: String,
        userMessageId: String,
        promptSnippet: String
    ): String {
        val existing = v2Agent.getCheckpointByMessageId(userMessageId)?.toEntity()
        if (existing != null) return existing.id
        v2Agent.insertCheckpointFull(
            id = checkpointId, sessionId = sessionId, userMessageId = userMessageId,
            promptSnippet = promptSnippet, createdAtMs = System.currentTimeMillis(),
        )
        return checkpointId
    }

    /** 关联一个文件快照（幂等：同 checkpointId+filePath 只插一次）。 */
    suspend fun attachFileSnapshot(
        snapshotId: String, checkpointId: String,
        filePath: String, snapshotRelativePath: String, changeType: String
    ) {
        val exists = v2Agent.countCheckpointFileSnapshot(checkpointId, filePath) > 0
        if (exists) return
        v2Agent.insertCheckpointFileSnapshot(
            id = snapshotId, checkpointId = checkpointId, filePath = filePath,
            snapshotRelativePath = snapshotRelativePath, changeType = changeType,
            createdAt = System.currentTimeMillis(),
        )
    }

    /** Phase 5 Postflight 失败：列出 checkpoint + 所有快照（用于文件还原）。 */
    suspend fun getCheckpointWithSnapshots(checkpointId: String):
            Pair<CheckpointEntity, List<CheckpointFileSnapshotEntity>>? {
        val ck = v2Agent.getCheckpointById(checkpointId)?.toEntity() ?: return null
        val snaps = v2Agent.listCheckpointFileSnapshots(checkpointId).map { it.toEntity() }
        return ck to snaps
    }

    suspend fun getByMessageId(messageId: String): CheckpointEntity? =
        v2Agent.getCheckpointByMessageId(messageId)?.toEntity()

    suspend fun listForSession(sessionId: String): List<CheckpointEntity> =
        v2Agent.listCheckpointsForSession(sessionId).map { it.toEntity() }

    /** 会话关闭 / 用户手动：清空 session 所有 checkpoint + 快照。 */
    suspend fun clearSession(sessionId: String) {
        v2Agent.deleteCheckpointFileSnapshotsBySession(sessionId)
        v2Agent.deleteCheckpointsBySession(sessionId)
    }

    /** 后台 job：清理 cutoff 之前的 checkpoint（防止 DB 膨胀）。 */
    suspend fun clearBefore(cutoffTimestamp: Long) {
        v2Agent.deleteCheckpointsBefore(cutoffTimestamp)
    }

    // ── Phase 4.2 Firestore：Entity ↔ Dto 映射入口 ─────────────────

    fun checkpointToDto(e: CheckpointEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "sessionId" to e.sessionId, "userMessageId" to e.userMessageId,
        "promptSnippet" to e.promptSnippet.take(200), "createdAt" to e.createdAtMs,
        "_lwwMs" to System.currentTimeMillis()
    )

    fun checkpointFromDto(m: Map<String, Any?>): CheckpointEntity = CheckpointEntity(
        id = m["id"] as? String ?: "",
        sessionId = m["sessionId"] as? String ?: "",
        userMessageId = m["userMessageId"] as? String ?: "",
        promptSnippet = m["promptSnippet"] as? String ?: "",
        createdAtMs = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )

    fun snapshotToDto(e: CheckpointFileSnapshotEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "checkpointId" to e.checkpointId, "filePath" to e.filePath,
        "snapshotRelativePath" to e.snapshotRelativePath, "changeType" to e.changeType,
        "createdAt" to e.createdAt, "_lwwMs" to System.currentTimeMillis()
    )

    fun snapshotFromDto(m: Map<String, Any?>): CheckpointFileSnapshotEntity =
        CheckpointFileSnapshotEntity(
            id = m["id"] as? String ?: "",
            checkpointId = m["checkpointId"] as? String ?: "",
            filePath = m["filePath"] as? String ?: "",
            snapshotRelativePath = m["snapshotRelativePath"] as? String ?: "",
            changeType = m["changeType"] as? String ?: "MODIFY",
            createdAt = (m["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
        )

    // ── 内部映射 ─────────────────────────────────────────────────────

    private fun com.R.codecore.datalayer.sqldelight.agent.Session_checkpoints.toEntity() = CheckpointEntity(
        id = id,
        sessionId = session_id,
        userMessageId = user_message_id,
        promptSnippet = prompt_snippet,
        createdAtMs = created_at_ms,
    )

    private fun com.R.codecore.datalayer.sqldelight.agent.Checkpoint_file_snapshots.toEntity() = CheckpointFileSnapshotEntity(
        id = id,
        checkpointId = checkpoint_id,
        filePath = file_path,
        snapshotRelativePath = snapshot_relative_path,
        changeType = change_type,
        createdAt = created_at,
    )
}