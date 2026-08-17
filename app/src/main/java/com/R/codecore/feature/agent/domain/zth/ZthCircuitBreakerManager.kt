package com.R.codecore.feature.agent.domain.zth

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.HallucinationFuseDao
import com.R.codecore.feature.agent.data.local.entity.HallucinationFuseEntity
import com.R.codecore.feature.agent.domain.permission.FailureClassification
import com.R.codecore.feature.agent.domain.permission.FuseState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.6 / C.4.3 ZTH 熔断状态机 Manager（纯业务类，不挂 UI Compose）。
 *
 * 双 Scope 隔离（PHASE-INV-1）：
 *   scope=GLOBAL   → 全局熔断（ZTH 总开关；RESET 需用户在 Red Banner 上点按钮，会话级熔断累计到全局）
 *   scope=SESSION  → 单会话隔离熔断（AI 在某对话里反复 hallucination，只熔断该对话，其他会话正常）
 *
 * 计数严格对齐 C.2.2 Decision Matrix 第 4 列「triggersFuseCountIncrement」：
 *   FailureClassification.triggersFuseCountIncrement = true → 双 scope failureCount++
 *   false → 不计数（例如 INFRA_HTTP_RATE_LIMITED 自动退避，不算幻觉）
 *
 * 阈值（档位差异 C.4.2）：
 *   tier=1(MINIMAL)  → FAIL_TRIP=5
 *   tier=2(BALANCED) → FAIL_TRIP=3
 *   tier=3(STRICT)   → FAIL_TRIP=2
 *   tier=0(DISABLED) → 永不熔断（直接 ALLOW）
 *
 * T_cool 冷却（OPEN → HALF_OPEN 自动重置）：严格按档位 5/3/2 min（不是秒）。
 * killSwitch-1：单向置位（true→false 不允许），一旦 UI 上「紧急关闭 ZTH」按下后，永远 OPEN。
 * killSwitch-2：DataStore 软标志（可关/开），优先级低。
 */
@Singleton
class ZthCircuitBreakerManager @Inject constructor(
    private val dao: HallucinationFuseDao
) {
    private companion object {
        const val TAG = "ZthCircuitBreakerMgr"
        const val MAX_CAS_ATTEMPTS = 3
        val FAIL_TRIP_BY_TIER: Map<Int, Int> = mapOf(1 to 5, 2 to 3, 3 to 2)
        val T_COOL_MIN_BY_TIER: Map<Int, Long> = mapOf(1 to 5L, 2 to 3L, 3 to 2L)
        const val HALF_OPEN_MAX_PROBE_FAILURES = 1 // 半开 1 次探针失败立即回 OPEN
        const val HALF_OPEN_PROBE_SUCCESSES_TO_CLOSE = 1 // 半开成功 1 次回 CLOSED
    }

    // 读取 Entity.state（String）→ FuseState；因为 Room 持久化存的是 .name（C.4.6 不变性）
    private val HallucinationFuseEntity.fuseState: FuseState
        get() = runCatching { FuseState.valueOf(state) }.getOrElse {
            FileLogger.w(TAG, "Entity $id fuseState=$state 无法解析 FuseState，兜底 CLOSED（可能 schema 迁移中）")
            FuseState.CLOSED
        }

    // ── 外部入口 1：记录一次 FailureClassification → 若 triggersFuseCountIncrement=true 计数 ──

    suspend fun recordFailure(sessionId: String?, tier: ZthPresetTier, cls: FailureClassification) {
        if (!cls.triggersFuseCountIncrement) return // C.2.2 第 4 列：false → 跳过（不变性 0 副作用）
        // 双 scope 都计数（会话级先挡，全局后挡）
        increment(sessionId = null, tier, cls.subClass.name)
        if (sessionId != null) increment(sessionId = sessionId, tier, cls.subClass.name)
    }

    // ── 外部入口 2：查询「当前是否允许执行」（Phase 5 StatefulAgentWorkflow 调） ──────────

    suspend fun isAllowed(sessionId: String?, tier: ZthPresetTier): AllowanceResult {
        if (tier == ZthPresetTier.DISABLED) return AllowanceResult.ALLOW
        val global = loadOrCreateGlobal()
        // kill-switch-1 单向置位：永远 BLOCK（不变性 KILL-1：不能自动清）
        if (global.killSwitch1Triggered) return AllowanceResult(
            false, FuseState.OPEN, "ZTH 全局 kill-switch-1 已激活，红横幅上点「一键回滚+重置」后才能恢复。"
        )
        // 冷却到期自动从 OPEN 切 HALF_OPEN（T_cool tier 分钟）
        val cooledGlobal = tryAutoCoolDown(global, tier)
        if (sessionId == null) return mapState(cooledGlobal.fuseState, "全局")

        val sessionEntity = loadOrCreateSession(sessionId)
        val cooledSession = tryAutoCoolDown(sessionEntity, tier)
        // 双 scope：任何一方 OPEN / TRANSITIONING → BLOCK
        return when {
            cooledSession.killSwitch1Triggered -> AllowanceResult(false, FuseState.OPEN,
                "会话级 kill-switch-1 已激活。"
            )
            cooledSession.fuseState == FuseState.OPEN || cooledSession.fuseState == FuseState.TRANSITIONING ->
                mapState(cooledSession.fuseState, "会话级")
            cooledGlobal.fuseState == FuseState.OPEN || cooledGlobal.fuseState == FuseState.TRANSITIONING ->
                mapState(cooledGlobal.fuseState, "全局")
            else -> AllowanceResult.ALLOW
        }
    }

    // ── 外部入口 3：HALF_OPEN 探针成功 → 计数（成功 1 次回 CLOSED） ──────────────

    suspend fun recordHalfOpenProbeSuccess(sessionId: String?, tier: ZthPresetTier) {
        val threshold = HALF_OPEN_PROBE_SUCCESSES_TO_CLOSE
        val g = loadOrCreateGlobal()
        if (g.fuseState == FuseState.HALF_OPEN) {
            // 这里简化：成功 1 次直接回 CLOSED（HALF_OPEN_PROBE_SUCCESSES_TO_CLOSE = 1）
            transitionTo(g, FuseState.CLOSED, TierContext(tier, sessionId), clearFailures = true)
        }
        if (sessionId != null) {
            val s = loadOrCreateSession(sessionId)
            if (s.fuseState == FuseState.HALF_OPEN) transitionTo(s, FuseState.CLOSED, TierContext(tier, sessionId), clearFailures = true)
        }
    }

    // ── 外部入口 4：HALF_OPEN 探针失败 → 立即回 OPEN ──────────────────────────

    suspend fun recordHalfOpenProbeFail(sessionId: String?, tier: ZthPresetTier, subClass: String) {
        val g = loadOrCreateGlobal()
        if (g.fuseState == FuseState.HALF_OPEN) transitionTo(g.copy(failureCount = g.failureCount + 1, lastTripSubclass = subClass),
            FuseState.OPEN, TierContext(tier, sessionId))
        if (sessionId != null) {
            val s = loadOrCreateSession(sessionId)
            if (s.fuseState == FuseState.HALF_OPEN) transitionTo(s.copy(failureCount = s.failureCount + 1, lastTripSubclass = subClass),
                FuseState.OPEN, TierContext(tier, sessionId))
        }
    }

    // ── 外部入口 5：用户强制 RESET（Banner 按钮 / kill-switch-2） ────────────────

    suspend fun userRequestedReset(sessionId: String?, tier: ZthPresetTier): Boolean {
        val list = mutableListOf<HallucinationFuseEntity>()
        list.add(loadOrCreateGlobal())
        if (sessionId != null) list.add(loadOrCreateSession(sessionId))
        var allOk = true
        for (e in list) {
            if (e.killSwitch1Triggered) {
                allOk = false
                continue // kill-switch-1 不能自动清（KILL-1 不变性）
            }
            if (e.fuseState == FuseState.OPEN || e.fuseState == FuseState.TRANSITIONING) {
                // 必经 TRANSITIONING → HALF_OPEN
                val tOk = transitionTo(e, FuseState.TRANSITIONING, TierContext(tier, sessionId))
                if (tOk) {
                    val e2 = dao.getById(e.id) ?: continue
                    transitionTo(e2, FuseState.HALF_OPEN, TierContext(tier, sessionId), clearFailures = true)
                } else allOk = false
            }
        }
        return allOk
    }

    // ── 内部核心：计数 + 超阈值 tripped → OPEN ─────────────────────────────────

    private suspend fun increment(sessionId: String?, tier: ZthPresetTier, subClass: String) {
        val (entity, scopeLabel) = if (sessionId == null) {
            loadOrCreateGlobal() to "global"
        } else loadOrCreateSession(sessionId) to "session#$sessionId"

        val threshold = FAIL_TRIP_BY_TIER[tier.tier.coerceIn(1, 3)] ?: return
        val newCount = entity.failureCount + 1
        val updated = entity.copy(
            failureCount = newCount,
            lastTripSubclass = subClass
        )
        if (entity.fuseState == FuseState.CLOSED && newCount >= threshold) {
            // 超阈值 → 切 OPEN + 写 openSinceMs
            val tripped = updated.copy(state = FuseState.OPEN.name, openSinceMs = System.currentTimeMillis())
            dao.upsert(tripped)
            FileLogger.i(TAG, "[$scopeLabel] 熔断 OPEN：failureCount=$newCount ≥ threshold=$threshold subClass=$subClass")
        } else {
            dao.upsert(updated)
        }
    }

    // ── 内部核心：LINK-INV CAS 事务状态迁移（乐观锁 MAX_CAS_ATTEMPTS 次） ────────

    private data class TierContext(val tier: ZthPresetTier, val sessionId: String?)

    private suspend fun transitionTo(
        entity: HallucinationFuseEntity,
        target: FuseState,
        ctx: TierContext,
        clearFailures: Boolean = false
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        var attempts = 0
        while (attempts < MAX_CAS_ATTEMPTS) {
            attempts++
            val current = dao.getVersion(entity.id) ?: run {
                // 不存在 → 先插初始
                dao.upsert(createInitial(entity.id, entity.scope, entity.scopeId))
                continue
            }
            val rows = dao.casUpdateState(entity.id, expectedVersion = current, target.name, nowMs)
            if (rows == 1) {
                // CAS 成功：再把附加字段（clearFailures/killSwitch 不用）写一次
                if (clearFailures) {
                    val e = dao.getById(entity.id) ?: return true
                    dao.upsert(e.copy(failureCount = 0, lastProbeAtMs = nowMs))
                } else {
                    val e = dao.getById(entity.id) ?: return true
                    val fix = when (target) {
                        FuseState.OPEN -> e.copy(openSinceMs = nowMs)
                        FuseState.HALF_OPEN -> e.copy(lastProbeAtMs = nowMs)
                        else -> e
                    }
                    dao.upsert(fix)
                }
                FileLogger.i(TAG, "CAS 迁移成功 id=${entity.id} ${entity.state}→$target 第 $attempts 次")
                return true
            }
            // CAS 冲突 → 下一轮重试（0 回退 15ms）
            kotlinx.coroutines.delay(15L * attempts)
        }
        FileLogger.e(TAG, "CAS 迁移失败 ${MAX_CAS_ATTEMPTS} 次 id=${entity.id}：LINK-INV 版本冲突，回滚 OPEN + kill-switch-1 置位")
        // 迁移失败 → 不变性 LINK-INV-FAIL：回滚 OPEN + kill-switch-1 单向置位
        dao.triggerKillSwitch1(entity.id, nowMs)
        return false
    }

    // ── 内部工具：T_cool 到期自动从 OPEN → HALF_OPEN（必经 LINK-INV CAS） ──────

    private suspend fun tryAutoCoolDown(entity: HallucinationFuseEntity, tier: ZthPresetTier): HallucinationFuseEntity {
        if (entity.fuseState != FuseState.OPEN) return entity
        val tMin = T_COOL_MIN_BY_TIER[tier.tier.coerceIn(1, 3)] ?: return entity
        val needMs = tMin * 60_000L
        val since = entity.openSinceMs
        if (since <= 0L || System.currentTimeMillis() - since < needMs) return entity
        // 自动冷却：先 TRANSITIONING → HALF_OPEN
        val ctx = TierContext(tier, if (entity.scope == "SESSION") entity.scopeId else null)
        transitionTo(entity, FuseState.TRANSITIONING, ctx)
        val t = dao.getById(entity.id) ?: return entity
        if (t.fuseState == FuseState.TRANSITIONING) {
            transitionTo(t, FuseState.HALF_OPEN, ctx, clearFailures = true)
        }
        return dao.getById(entity.id) ?: entity
    }

    private fun mapState(state: FuseState, label: String): AllowanceResult = when (state) {
        FuseState.CLOSED, FuseState.HALF_OPEN -> AllowanceResult(true, state, "$label 正常放行 (state=${state.name})")
        FuseState.OPEN -> AllowanceResult(false, state, "$label 熔断 OPEN：请先点 Banner 上的「重置熔断」或等待 T_cool 到期。")
        FuseState.TRANSITIONING -> AllowanceResult(false, state, "$label LINK-INV 迁移中（TRANSITIONING），稍后重试。")
    }

    // ── 懒创建初始实体（scope=GLOBAL/SESSION scopeId 锁死 composeGlobalId/composeSessionId）

    private suspend fun loadOrCreateGlobal(): HallucinationFuseEntity =
        dao.getGlobal() ?: createInitial(HallucinationFuseEntity.composeGlobalId(), "GLOBAL", HallucinationFuseEntity.GLOBAL_SCOPE_ID).also { dao.upsert(it) }

    private suspend fun loadOrCreateSession(sessionId: String): HallucinationFuseEntity =
        dao.getBySession(sessionId) ?: createInitial(HallucinationFuseEntity.composeSessionId(sessionId), "SESSION", sessionId).also { dao.upsert(it) }

    private fun createInitial(id: String, scope: String, scopeId: String) = HallucinationFuseEntity(
        id = id,
        scope = scope,
        scopeId = scopeId,
        state = FuseState.CLOSED.name,
        linkageVersion = 0L,
        failureCount = 0
    )

    // 为了方便拿到 entity
    private suspend fun HallucinationFuseDao.getById(id: String): HallucinationFuseEntity? =
        getAllOnce().firstOrNull { it.id == id }
}

/**
 * Manager 对外「允许/拒绝」结果（含原因，Phase 5 StatefulAgentWorkflow 传进 FailureClassifier 直接造 FailureClassification）。
 */
data class AllowanceResult(
    val allowed: Boolean,
    val currentState: FuseState,
    val reason: String? = null
) {
    companion object {
        val ALLOW = AllowanceResult(true, FuseState.CLOSED)
    }
}
