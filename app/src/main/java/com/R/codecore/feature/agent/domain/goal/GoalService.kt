package com.R.codecore.feature.agent.domain.goal

import com.R.codecore.datalayer.DataReadMode
import com.R.codecore.datalayer.DataReadModeHolder
import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_goals as V2AgentGoal
import com.R.codecore.feature.agent.data.local.dao.GoalDao
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.agent.data.local.entity.GoalEntity
import com.R.codecore.feature.agent.data.local.entity.GoalStatus
import androidx.room.withTransaction
import java.util.UUID
import javax.inject.Inject

/**
 * 会话级「任务目标」状态机服务（对齐 DSH GoalService 契约）：
 *
 * - 每会话唯一当前 goal：激活新目标时把旧 ACTIVE 目标置 ABANDONED（CAS，防并发覆盖），再插入新目标；
 * - 变更用 compare-and-set（revision 单调递增）：读→改→按旧 revision 更新，冲突返回 null；
 * - activation 不额外持久化激活动作本身，只反映为状态变更（revision 递增）。
 *
 * 供 [goal 工具] 与 workflow（每轮 step 前注入当前目标 / 目标变更事件）共用。
 */
class GoalService @Inject constructor(
    private val goalDao: GoalDao,
    private val database: AgentDatabase,
    private val v2Agent: V2AgentRepository,
    private val readMode: DataReadModeHolder,
) {

    private suspend fun isV2(): Boolean = readMode.currentMode() == DataReadMode.V2

    /** 读取会话当前 ACTIVE 目标；无则返回 null。 */
    suspend fun getActive(sessionId: String): GoalEntity? =
        if (isV2()) v2Agent.getActiveGoalBySession(sessionId)?.toEntity()
        else goalDao.getActiveBySessionOnce(sessionId)

    /**
     * 激活新目标（会话内幂等替换）：事务内把旧 ACTIVE 目标置 ABANDONED，再插入新 ACTIVE 目标。
     * @return 新激活的目标实体。
     */
    suspend fun activate(sessionId: String, text: String, roundSeq: Int = 0): GoalEntity {
        val now = System.currentTimeMillis()
        val entity = GoalEntity(
            goalId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            text = text,
            status = GoalStatus.ACTIVE.name,
            revision = 0,
            parentGoalId = "",
            roundSeq = roundSeq,
            createdAtMs = now,
            updatedAtMs = now
        )
        if (isV2()) {
            v2Agent.runInTx { tx ->
                tx.activateGoal(
                    sessionId = sessionId,
                    old = v2Agent.getActiveGoalBySessionBlocking(sessionId),
                    goalId = entity.goalId,
                    text = entity.text,
                    status = entity.status,
                    revision = entity.revision.toLong(),
                    parentGoalId = entity.parentGoalId,
                    roundSeq = entity.roundSeq.toLong(),
                    createdAtMs = entity.createdAtMs,
                    updatedAtMs = entity.updatedAtMs
                )
            }
        } else {
            database.withTransaction {
                goalDao.getActiveBySessionOnce(sessionId)?.let { old ->
                    // CAS 置 ABANDONED：若 revision 已变（并发激活），本次放弃不覆盖新目标
                    goalDao.casUpdateStatusAndText(
                        goalId = old.goalId,
                        status = GoalStatus.ABANDONED.name,
                        text = old.text,
                        newRevision = old.revision + 1,
                        expectedRevision = old.revision,
                        updatedAtMs = now
                    )
                }
                goalDao.upsert(entity)
            }
        }
        return entity
    }

    /** 按 id 读取目标。 */
    suspend fun getById(goalId: String): GoalEntity? =
        if (isV2()) v2Agent.getGoalById(goalId)?.toEntity() else goalDao.getById(goalId)

    /**
     * CAS 更新目标文本（修订号冲突时返回 null，不覆盖并发写入）。
     * 目标已终态（DONE / ABANDONED）时拒绝修改并返回 null。
     */
    suspend fun updateText(goalId: String, newText: String): GoalEntity? {
        val existing = (if (isV2()) v2Agent.getGoalById(goalId)?.toEntity() else goalDao.getById(goalId)) ?: return null
        if (existing.statusEnum() == GoalStatus.DONE || existing.statusEnum() == GoalStatus.ABANDONED) {
            return null
        }
        return casSet(goalId, existing.statusEnum(), newText)
    }

    /** CAS 置状态（DONE / ABANDONED / PROPOSED / ACTIVE）。修订号冲突或目标不存在时返回 null。 */
    suspend fun setStatus(goalId: String, status: GoalStatus): GoalEntity? {
        val existing = (if (isV2()) v2Agent.getGoalById(goalId)?.toEntity() else goalDao.getById(goalId)) ?: return null
        if (existing.statusEnum() == status) return existing
        return casSet(goalId, status, existing.text)
    }

    /** 完成目标（置 DONE）。 */
    suspend fun complete(goalId: String): GoalEntity? = setStatus(goalId, GoalStatus.DONE)

    /** 放弃目标（置 ABANDONED）。 */
    suspend fun abandon(goalId: String): GoalEntity? = setStatus(goalId, GoalStatus.ABANDONED)

    private suspend fun casSet(goalId: String, status: GoalStatus, text: String): GoalEntity? {
        val now = System.currentTimeMillis()
        val existing = (if (isV2()) v2Agent.getGoalById(goalId)?.toEntity() else goalDao.getById(goalId)) ?: return null
        val updated = if (isV2()) {
            v2Agent.casUpdateGoalStatusAndText(
                goalId = goalId,
                status = status.name,
                text = text,
                newRevision = existing.revision.toLong() + 1,
                expectedRevision = existing.revision.toLong(),
                updatedAtMs = now
            )
        } else {
            goalDao.casUpdateStatusAndText(
                goalId = goalId,
                status = status.name,
                text = text,
                newRevision = existing.revision + 1,
                expectedRevision = existing.revision,
                updatedAtMs = now
            ).toLong()
        }
        return if (updated > 0) getById(goalId) else null
    }

    // ── V2（SQLDelight）↔ Room Entity 映射 ──────────────────────────────

    private fun V2AgentGoal.toEntity() = GoalEntity(
        goalId = goal_id,
        sessionId = session_id,
        text = text,
        status = status,
        revision = revision.toInt(),
        parentGoalId = parent_goal_id,
        roundSeq = round_seq.toInt(),
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms
    )
}
