package com.deep.rcode.feature.agent.data.repository

import com.deep.rcode.feature.agent.data.local.dao.HallucinationFuseDao
import com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity
import com.deep.rcode.feature.agent.domain.permission.FuseState
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * C.4.6 熔断 Repository（薄封装 HallucinationFuseDao）。
 *
 * 业务逻辑（计数 / 冷却 / CAS 迁移 / kill-switch）在 [ZthCircuitBreakerManager]，
 * 这里只提供：
 *   - 供 Phase 4.2 SyncManager 拉/推的 getAllOnce / upsertAll
 *   - 供 UI Red Banner observe 用的 observeGlobalAndSession
 *   - Firestore Entity↔Dto 映射
 *
 * 不变性：
 *   FUSE-REPO-INV-1：绝不在 Repository 层直接修改 linkageVersion / state（必须走 Manager CAS）
 *   FUSE-REPO-INV-2：跨设备同步只允许 CLOSED ↔ HALF_OPEN；OPEN / killSwitch1 必须本地手动清
 *   （C.4.2 KILL-1：killSwitch1Triggered 单向置位，不能由 Firestore 覆盖为 false）
 */
@Singleton
class ZthCircuitBreakerRepository @Inject constructor(
    private val dao: HallucinationFuseDao
) {

    /** UI Red Banner：实时观察全局 + 会话级 fuse。 */
    fun observeGlobalAndSession(sessionId: String): Flow<List<HallucinationFuseEntity>> =
        dao.observeGlobalAndSession(sessionId)

    /** Phase 4.2 Sync：全量拉取本地（push 到 Firestore）。 */
    suspend fun getAll(): List<HallucinationFuseEntity> = dao.getAllOnce()

    /** Phase 4.2 Sync：Firestore pull → 本地合并（按 KILL-1 不变性过滤 killSwitch1=true）。 */
    suspend fun mergeFromRemote(remoteList: List<HallucinationFuseEntity>) {
        val locals = dao.getAllOnce().associateBy { it.id }
        val toUpsert = mutableListOf<HallucinationFuseEntity>()
        for (r in remoteList) {
            val l = locals[r.id]
            if (l == null) {
                toUpsert.add(r)
                continue
            }
            // FUSE-REPO-INV-2：本地 killSwitch1Triggered=true → 拒绝远程覆盖为 false
            val merged = if (l.killSwitch1Triggered && !r.killSwitch1Triggered) {
                r.copy(killSwitch1Triggered = true, state = FuseState.OPEN.name)
            } else r
            toUpsert.add(merged)
        }
        if (toUpsert.isNotEmpty()) dao.upsertAll(toUpsert)
    }

    // ── Phase 4.2 Firestore：Entity ↔ Dto 映射 ─────────────────────────

    fun toDto(e: HallucinationFuseEntity): Map<String, Any?> = mapOf(
        "id" to e.id, "scope" to e.scope, "scopeId" to e.scopeId,
        "state" to e.state, "linkageVersion" to e.linkageVersion,
        "failureCount" to e.failureCount, "openSinceMs" to e.openSinceMs,
        "lastProbeAtMs" to e.lastProbeAtMs,
        "killSwitch1Triggered" to e.killSwitch1Triggered,
        "killSwitch2SoftDisabled" to e.killSwitch2SoftDisabled,
        "lastTripSubclass" to e.lastTripSubclass, "updatedAtMs" to e.updatedAtMs,
        "_lwwMs" to System.currentTimeMillis()
    )

    fun fromDto(m: Map<String, Any?>): HallucinationFuseEntity = HallucinationFuseEntity(
        id = m["id"] as? String ?: "",
        scope = m["scope"] as? String ?: "GLOBAL",
        scopeId = m["scopeId"] as? String ?: HallucinationFuseEntity.GLOBAL_SCOPE_ID,
        state = m["state"] as? String ?: FuseState.CLOSED.name,
        linkageVersion = (m["linkageVersion"] as? Number)?.toLong() ?: 0L,
        failureCount = (m["failureCount"] as? Number)?.toInt() ?: 0,
        openSinceMs = (m["openSinceMs"] as? Number)?.toLong() ?: 0L,
        lastProbeAtMs = (m["lastProbeAtMs"] as? Number)?.toLong() ?: 0L,
        killSwitch1Triggered = (m["killSwitch1Triggered"] as? Boolean) ?: false,
        killSwitch2SoftDisabled = (m["killSwitch2SoftDisabled"] as? Boolean) ?: false,
        lastTripSubclass = m["lastTripSubclass"] as? String,
        updatedAtMs = (m["updatedAtMs"] as? Number)?.toLong() ?: System.currentTimeMillis()
    )
}
