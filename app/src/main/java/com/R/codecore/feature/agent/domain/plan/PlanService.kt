package com.R.codecore.feature.agent.domain.plan

import com.R.codecore.datalayer.repository.AgentRepository as V2AgentRepository
import com.R.codecore.datalayer.sqldelight.agent.Agent_plans as V2AgentPlan
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.entity.PlanEntity
import com.R.codecore.feature.agent.data.local.entity.PlanStatus
import java.util.UUID
import javax.inject.Inject

/**
 * 会话级「计划协作状态」服务（对齐 DSH plan mode + Claude Code Plan/Spec）。
 *
 * 持久化当前计划（title + steps(JSON) + 生命周期 status），并保存未获批前的
 * [pendingSelection]——由 workflow 每轮 step 前追加到模型请求（对齐 DSH plan mode）；
 * 用户批准后置 [PlanStatus.APPROVED] 并清空 pendingSelection。
 *
 * 计划生命周期（[PlanStatus]）：DRAFT（含待定选择）→ APPROVED → EXECUTING → COMPLETED，
 * 被替换/放弃置 ABANDONED。每会话单计划（propose 时旧 DRAFT 置 ABANDONED）。
 *
 * 供 [plan 工具] 与 workflow（每轮 step 前注入 pendingSelection）共用。
 */
class PlanService @Inject constructor(
    private val v2Agent: V2AgentRepository,
) {

    /** 读取会话最近一份计划；无则返回 null。 */
    suspend fun getLatest(sessionId: String): PlanEntity? =
        v2Agent.getLatestPlanBySession(sessionId)?.toEntity()

    /** 按 id 读取计划；无则返回 null。 */
    suspend fun getById(planId: String): PlanEntity? =
        v2Agent.getPlanById(planId)?.toEntity()

    /** 按 id 整行更新（供 plan 工具在非终态校验后落库）。目标不存在返回 null。 */
    suspend fun update(planId: String, plan: PlanEntity): PlanEntity? {
        if (getById(planId) == null) return null
        v2Agent.upsertPlan(
            planId = plan.planId,
            sessionId = plan.sessionId,
            title = plan.title,
            steps = plan.steps,
            status = plan.status,
            pendingSelection = plan.pendingSelection,
            createdAtMs = plan.createdAtMs,
            updatedAtMs = plan.updatedAtMs
        )
        return getById(planId)
    }

    /**
     * 提议新计划（DRAFT）：事务内把会话旧计划置 ABANDONED（若仍非终态），再插入新 DRAFT。
     * @param stepsJson steps 的 JSON 数组序列化（`[ {text, status} ]`）。
     * @param pendingSelection 供用户选择的待定方案文本（可为空）。
     */
    suspend fun propose(sessionId: String, title: String, stepsJson: String, pendingSelection: String): PlanEntity {
        val now = System.currentTimeMillis()
        val entity = PlanEntity(
            planId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            title = title,
            steps = stepsJson,
            status = PlanStatus.DRAFT.name,
            pendingSelection = pendingSelection,
            createdAtMs = now,
            updatedAtMs = now
        )
        v2Agent.runInTx { tx ->
            tx.proposePlan(
                sessionId = sessionId,
                old = v2Agent.getLatestPlanBySessionBlocking(sessionId),
                planId = entity.planId,
                title = entity.title,
                steps = entity.steps,
                status = entity.status,
                pendingSelection = entity.pendingSelection,
                createdAtMs = entity.createdAtMs,
                updatedAtMs = entity.updatedAtMs
            )
        }
        FileLogger.d(TAG, "propose: session=$sessionId planId=${entity.planId} status=DRAFT")
        return entity
    }

    /** 更新待定选择（用户选中某方案后落库，供下轮注入）。 */
    suspend fun setPendingSelection(planId: String, pendingSelection: String): PlanEntity? {
        val existing = getById(planId) ?: return null
        v2Agent.updatePlanContent(
            planId = planId,
            status = existing.status,
            steps = existing.steps,
            pendingSelection = pendingSelection,
            updatedAtMs = System.currentTimeMillis()
        )
        return getById(planId)
    }

    /** 置状态（APPROVED 时清空 pendingSelection）。目标不存在返回 null。 */
    suspend fun setStatus(planId: String, status: PlanStatus): PlanEntity? {
        val existing = getById(planId) ?: return null
        val nextPending = if (status == PlanStatus.APPROVED) "" else existing.pendingSelection
        v2Agent.updatePlanContent(
            planId = planId,
            status = status.name,
            steps = existing.steps,
            pendingSelection = nextPending,
            updatedAtMs = System.currentTimeMillis()
        )
        return getById(planId)
    }

    /** 批准计划（DRAFT → APPROVED，清空 pendingSelection）。 */
    suspend fun approve(planId: String): PlanEntity? = setStatus(planId, PlanStatus.APPROVED)

    /** 放弃计划（置 ABANDONED）。 */
    suspend fun abandon(planId: String): PlanEntity? = setStatus(planId, PlanStatus.ABANDONED)

    // ── V2（SQLDelight）↔ Room Entity 映射 ──────────────────────────────

    private fun V2AgentPlan.toEntity() = PlanEntity(
        planId = plan_id,
        sessionId = session_id,
        title = title,
        steps = steps,
        status = status,
        pendingSelection = pending_selection,
        createdAtMs = created_at_ms,
        updatedAtMs = updated_at_ms
    )

    private companion object {
        const val TAG = "PlanService"
    }
}
