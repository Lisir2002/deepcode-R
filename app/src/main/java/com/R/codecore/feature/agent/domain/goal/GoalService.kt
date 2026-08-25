package com.R.codecore.feature.agent.domain.goal

import androidx.room.withTransaction
import com.R.codecore.feature.agent.data.local.dao.GoalDao
import com.R.codecore.feature.agent.data.local.database.AgentDatabase
import com.R.codecore.feature.agent.data.local.entity.GoalEntity
import com.R.codecore.feature.agent.data.local.entity.GoalStatus
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
    private val database: AgentDatabase
) {

    /** 读取会话当前 ACTIVE 目标；无则返回 null。 */
    suspend fun getActive(sessionId: String): GoalEntity? =
        goalDao.getActiveBySessionOnce(sessionId)

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
        return entity
    }

    /** 按 id 读取目标。 */
    suspend fun getById(goalId: String): GoalEntity? = goalDao.getById(goalId)

    /**
     * CAS 更新目标文本（修订号冲突时返回 null，不覆盖并发写入）。
     * 目标已终态（DONE / ABANDONED）时拒绝修改并返回 null。
     */
    suspend fun updateText(goalId: String, newText: String): GoalEntity? {
        val existing = goalDao.getById(goalId) ?: return null
        if (existing.statusEnum() == GoalStatus.DONE || existing.statusEnum() == GoalStatus.ABANDONED) {
            return null
        }
        return casSet(goalId, existing.statusEnum(), newText)
    }

    /** CAS 置状态（DONE / ABANDONED / PROPOSED / ACTIVE）。修订号冲突或目标不存在时返回 null。 */
    suspend fun setStatus(goalId: String, status: GoalStatus): GoalEntity? {
        val existing = goalDao.getById(goalId) ?: return null
        if (existing.statusEnum() == status) return existing
        return casSet(goalId, status, existing.text)
    }

    /** 完成目标（置 DONE）。 */
    suspend fun complete(goalId: String): GoalEntity? = setStatus(goalId, GoalStatus.DONE)

    /** 放弃目标（置 ABANDONED）。 */
    suspend fun abandon(goalId: String): GoalEntity? = setStatus(goalId, GoalStatus.ABANDONED)

    private suspend fun casSet(goalId: String, status: GoalStatus, text: String): GoalEntity? {
        val now = System.currentTimeMillis()
        val existing = goalDao.getById(goalId) ?: return null
        val updated = goalDao.casUpdateStatusAndText(
            goalId = goalId,
            status = status.name,
            text = text,
            newRevision = existing.revision + 1,
            expectedRevision = existing.revision,
            updatedAtMs = now
        )
        return if (updated > 0) goalDao.getById(goalId) else null
    }
}
