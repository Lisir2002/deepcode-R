package com.core.deepcode.feature.agent.domain.checkpoint

import android.content.Context
import com.core.deepcode.datalayer.repository.AgentRepository as V2AgentRepository
import com.core.deepcode.datalayer.sqldelight.agent.Checkpoint_file_snapshots as V2Snapshot
import com.core.deepcode.datalayer.sqldelight.agent.Session_checkpoints as V2Checkpoint
import com.core.deepcode.feature.agent.data.local.entity.CheckpointEntity
import com.core.deepcode.feature.agent.data.local.entity.CheckpointFileSnapshotEntity
import com.core.deepcode.feature.workspace.domain.FileAccessProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CheckpointManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val v2Agent: V2AgentRepository
) {
    // 检查点备份根路径: <filesDir>/checkpoints/<sessionId>/<checkpointId>/
    private val baseCheckpointDir: File
        get() = File(context.filesDir, "checkpoints")

    @Volatile
    private var activeCheckpointId: String? = null

    /**
     * 在用户发送新消息时调用，创建一个新的 Checkpoint 节点
     */
    suspend fun createCheckpoint(
        sessionId: String,
        userMessageId: String,
        prompt: String
    ): CheckpointEntity = withContext(Dispatchers.IO) {
        val checkpointId = UUID.randomUUID().toString()
        val snippet = if (prompt.length > 60) prompt.take(60) + "..." else prompt
        v2Agent.insertCheckpointFull(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAtMs = System.currentTimeMillis()
        )
        activeCheckpointId = checkpointId
        CheckpointEntity(
            id = checkpointId,
            sessionId = sessionId,
            userMessageId = userMessageId,
            promptSnippet = snippet,
            createdAtMs = System.currentTimeMillis()
        )
    }

    fun setActiveCheckpointId(checkpointId: String?) {
        activeCheckpointId = checkpointId
    }

    /**
     * 在工具（editFile/writeFile）将要修改或新建文件前调用。
     * 若该文件在当前 Checkpoint 周期内未快照过，则保存其原始状态。
     */
    suspend fun beforeFileModified(
        sessionId: String,
        filePath: String
    ) = withContext(Dispatchers.IO) {
        val checkpointId = activeCheckpointId ?: return@withContext
        val targetFile = File(filePath)

        // 查重：同一个 checkpointId 内对同一文件只保留最原始的一次快照
        val existingCount: Long = v2Agent.countCheckpointFileSnapshot(checkpointId, filePath)
        if (existingCount > 0L) {
            return@withContext
        }

        val snapshotDir = File(baseCheckpointDir, "$sessionId/$checkpointId")
        if (!snapshotDir.exists()) {
            snapshotDir.mkdirs()
        }

        val exists = fileAccess.exists(filePath)
        val changeType = if (exists) "MODIFY" else "CREATE"
        val snapshotFileName = "${UUID.randomUUID()}_${File(filePath).name}"
        val snapshotFile = File(snapshotDir, snapshotFileName)

        if (changeType == "MODIFY") {
            val originalContent = fileAccess.readFile(filePath)
            snapshotFile.writeText(originalContent)
        } else {
            snapshotFile.writeText("") // 标示创建空记录
        }

        v2Agent.insertCheckpointFileSnapshot(
            id = UUID.randomUUID().toString(),
            checkpointId = checkpointId,
            filePath = filePath,
            snapshotRelativePath = "$sessionId/$checkpointId/$snapshotFileName",
            changeType = changeType,
            createdAt = System.currentTimeMillis()
        )
    }

    /**
     * 将代码回滚到指定 Checkpoint 节点的初始状态
     */
    suspend fun restoreCodeToCheckpoint(
        sessionId: String,
        targetCheckpointId: String
    ): Int = withContext(Dispatchers.IO) {
        val allCheckpoints = v2Agent.listCheckpointsForSession(sessionId).map { it.toEntity() }
        val targetIndex = allCheckpoints.indexOfFirst { it.id == targetCheckpointId }
        if (targetIndex == -1) return@withContext 0

        // 收集 targetCheckpointId 及其之后所有 Checkpoint 的快照，倒序还原
        val checkpointsToRollback = allCheckpoints.subList(targetIndex, allCheckpoints.size).reversed()
        var restoredFileCount = 0

        for (cp in checkpointsToRollback) {
            val snapshots = v2Agent.listCheckpointFileSnapshots(cp.id).map { it.toEntity() }
            for (snapshot in snapshots) {
                val snapshotFile = File(baseCheckpointDir, snapshot.snapshotRelativePath)

                if (snapshot.changeType == "MODIFY") {
                    if (snapshotFile.exists()) {
                        val content = snapshotFile.readText()
                        fileAccess.writeFile(snapshot.filePath, content, overwrite = true)
                        restoredFileCount++
                    }
                } else if (snapshot.changeType == "CREATE") {
                    // 若是原先新建的文件，回滚时安全删除
                    if (fileAccess.exists(snapshot.filePath)) {
                        fileAccess.delete(snapshot.filePath)
                        restoredFileCount++
                    }
                }
            }
        }
        restoredFileCount
    }

    /**
     * 删除 Session 关联的所有 Checkpoint 快照与记录
     */
    suspend fun clearSessionCheckpoints(sessionId: String) = withContext(Dispatchers.IO) {
        v2Agent.deleteCheckpointFileSnapshotsBySession(sessionId)
        v2Agent.deleteCheckpointsBySession(sessionId)
        val sessionDir = File(baseCheckpointDir, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }
    }

    // ── V2（SQLDelight）↔ Room Entity 映射 ──────────────────────────────

    private fun V2Checkpoint.toEntity() = CheckpointEntity(
        id = id,
        sessionId = session_id,
        userMessageId = user_message_id,
        promptSnippet = prompt_snippet,
        createdAtMs = created_at_ms
    )

    private fun V2Snapshot.toEntity() = CheckpointFileSnapshotEntity(
        id = id,
        checkpointId = checkpoint_id,
        filePath = file_path,
        snapshotRelativePath = snapshot_relative_path,
        changeType = change_type
    )
}
