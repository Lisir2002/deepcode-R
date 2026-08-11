package com.deep.rcode.feature.agent.data.remote.zth

import com.deep.rcode.core.security.ZthSharedSyncKeyStore
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.agent.data.repository.ZthCapabilityAuditRepository
import com.deep.rcode.feature.agent.data.repository.ZthCheckpointRepository
import com.deep.rcode.feature.agent.data.repository.ZthCircuitBreakerRepository
import com.deep.rcode.feature.agent.data.repository.ZthConfirmationCardRepository
import com.deep.rcode.feature.agent.data.repository.ZthPlanApprovalRepository
import com.deep.rcode.feature.agent.data.repository.ZthTelemetryRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * C.4.18 Firestore 双向同步 Manager（接口 + Placeholder 实现）。
 *
 * ### Phase 4 设计约束（Preflight P12 纠正）
 * 项目目前未引入：
 *  - Firebase/Firestore BOM SDK
 *  - Argon2id KDF 库
 *  - BIP-39 助记词库
 * 因此 **本 Manager 采用「接口契约 + Placeholder 实现」**：
 *    - 所有读写 Firestore 的方法在 Placeholder 中返回 SyncResult.DependencyNotReady
 *    - LWW 冲突合并算法作为**纯函数**单独暴露（无 SDK 依赖，可 JUnit 100% 覆盖）
 *    - Envelope（ct/hmac/updatedAtMs/deviceId）结构在此文件中定义为数据类
 *
 * ### 真实实现时（Phase 4 加完依赖后）只需要：
 *  1. 在 build.gradle.kts 加 firebase-bom + argon2 + bip39 依赖
 *  2. 写 RealZthSharedSyncKeyStore（Argon2id + BIP-39 8 词）
 *  3. 写 RealZthFirestoreSyncManager（FirebaseFirestore.getInstance() + 6 个 collection）
 *
 * ### 不变性
 *   SYNC-INV-1：shared_sync_key 永远不出现在 Firestore / 日志（本类所有方法绝不打印 key）
 *   SYNC-INV-2：LWW 只比较 document._lwwMs，大者赢；相等时用 deviceId 字典序破局（单调一致）
 *   SYNC-INV-3：killSwitch1Triggered=true 的 fuse 文档，merge 时绝不能被远程 false 覆盖
 *   SYNC-INV-4：pull 时先验证 hmac，失败则丢弃此文档并写 SYNC.CONFLICT 遥测（防止非法写入）
 */
@Singleton
class ZthFirestoreSyncManager @Inject constructor(
    private val keyStore: ZthSharedSyncKeyStore,
    private val mapper: ZthEntityMapper,
    private val sentinelRepo: ZthConfirmationCardRepository,
    private val fuseRepo: ZthCircuitBreakerRepository,
    private val planRepo: ZthPlanApprovalRepository,
    private val capRepo: ZthCapabilityAuditRepository,
    private val ckptRepo: ZthCheckpointRepository,
    private val telemetry: ZthTelemetryRepository
) {

    private companion object {
        const val TAG = "ZthFirestoreSyncMgr"
        /** 6 个 Firestore collection 名（真实实现时作为 collection() 参数）。 */
        const val COL_SENTINELS = "zth_sentinels"
        const val COL_FUSES = "zth_fuses"
        const val COL_REJECTION_AUDITS = "zth_rejection_audits"
        const val COL_HARD_DELETE_AUDITS = "zth_hard_delete_audits"
        const val COL_L0_LOGS = "zth_l0_restore_logs"
        const val COL_TELEMETRY = "zth_telemetry"
    }

    // ────────────────────────────────────────────────────────────────────────
    // 对外同步结果
    // ────────────────────────────────────────────────────────────────────────

    sealed interface SyncResult {
        data class Success(val pushed: Int, val pulled: Int, val conflictsResolved: Int) : SyncResult
        data class PartialFailure(val reason: String, val pushed: Int, val pulled: Int) : SyncResult
        /** 依赖未就绪（Firestore SDK / BIP-39 / Argon2id 未引入）→ UI 显示「Phase 4 激活」。 */
        object DependencyNotReady : SyncResult
        /** 用户还未 generateSharedKey / importFromMnemonic → 需先去设置页。 */
        object SharedKeyNotReady : SyncResult
    }

    /**
     * 双向同步主入口（设置页「立即同步」按钮 / 后台 job 每 30min 调一次）。
     * Placeholder 实现：若 !keyStore.isReady() → SharedKeyNotReady；否则 DependencyNotReady。
     * 真实实现时：pushLocal → pullRemote → mergeLww → applyLocal。
     */
    suspend fun syncNow(deviceId: String): SyncResult = withContext(Dispatchers.IO) {
        if (!keyStore.isReady()) return@withContext SyncResult.SharedKeyNotReady
        // Phase 4：此处加真实 Firestore SDK 调用（目前占位）
        FileLogger.i(TAG, "syncNow：Firestore SDK 未引入（Phase 4 后激活），deviceId=$deviceId")
        SyncResult.DependencyNotReady
    }

    // ────────────────────────────────────────────────────────────────────────
    // LWW 冲突合并算法（纯函数，可 JUnit 单测；Phase 4 真实 Syncer 内部调用）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * LWW 合并 2 份文档（本地 vs 远程）：比较 _lwwMs，大者赢。
     * 若 _lwwMs 相等 → 用 updatedAtDeviceId 字典序大的赢（确保跨设备单调一致）。
     *
     * @param local Envelope + payload（本地 Room 读出）
     * @param remote Envelope + payload（Firestore pull 下来）
     * @return LwwMergeResult.WIN_LOCAL / WIN_REMOTE / KILLSWITCH_PROTECTED
     */
    fun <T> mergeLww(
        local: LwwEnvelope<T>,
        remote: LwwEnvelope<T>,
        killSwitchProtected: Boolean = false
    ): LwwMergeResult<T> {
        // SYNC-INV-3：killSwitch 保护（本地 killSwitch1Triggered=true 且远程 false → 强制本地赢）
        if (killSwitchProtected) {
            val localHasKill = (local.payload as? HasKillSwitch1)?.killSwitch1Triggered == true
            val remoteClear = (remote.payload as? HasKillSwitch1)?.killSwitch1Triggered != true
            if (localHasKill && remoteClear) {
                return LwwMergeResult.KILLSWITCH_PROTECTED(local)
            }
        }
        return when {
            local.lwwMs > remote.lwwMs -> LwwMergeResult.WIN_LOCAL(local)
            local.lwwMs < remote.lwwMs -> LwwMergeResult.WIN_REMOTE(remote)
            else -> {
                // tie-break by deviceId（字典序大的赢；保证结果确定）
                if (local.deviceId >= remote.deviceId)
                    LwwMergeResult.WIN_LOCAL(local)
                else
                    LwwMergeResult.WIN_REMOTE(remote)
            }
        }
    }

    /**
     * 专用：Fuse 表 merge（额外满足 SYNC-INV-3：killSwitch1Triggered 单向置位）。
     * 直接调用上面泛型 mergeLww + killSwitchProtected=true + HasKillSwitch1 接口。
     */
    fun mergeFuseLww(
        local: LwwEnvelope<ZthFuseFirestoreDto>,
        remote: LwwEnvelope<ZthFuseFirestoreDto>
    ): LwwMergeResult<ZthFuseFirestoreDto> = mergeLww(
        local = local.copy(payload = local.payload),
        remote = remote,
        killSwitchProtected = true
    )

    // ────────────────────────────────────────────────────────────────────────
    // 对外数据结构（真实 Syncer 与单测共用）
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Firestore 文档外层 envelope（通用；任何 collection 文档都带这 5 列 + 内部 payload T）。
     * 真实 Firestore 文档字段结构：
     *   ct: AES-GCM(shared_sync_key, json_encode(payload)) base64
     *   hmac: HMAC-SHA256(shared_sync_key, ct) base64 （SYNC-INV-4 校验）
     *   _lwwMs: Long （LWW 时间戳）
     *   deviceId: String （tie-break）
     *   schemaVer: Int = 1（将来 schema 变更用）
     */
    data class LwwEnvelope<T>(
        val docId: String,
        val payload: T,
        val lwwMs: Long,
        val deviceId: String,
        val schemaVer: Int = 1,
        val ct: String? = null,       // 真实 Syncer 填充；单测可留空
        val hmac: String? = null      // 真实 Syncer 填充；单测可留空
    )

    /** Lww merge 结果。 */
    sealed interface LwwMergeResult<T> {
        val winner: LwwEnvelope<T>
        /** 本地时间戳更新 → 保留本地。 */
        data class WIN_LOCAL<T>(override val winner: LwwEnvelope<T>) : LwwMergeResult<T>
        /** 远程更新 → 应用远程（本地 DB upsert）。 */
        data class WIN_REMOTE<T>(override val winner: LwwEnvelope<T>) : LwwMergeResult<T>
        /** SYNC-INV-3 killSwitch 保护 → 强制本地赢，保留 killSwitch1Triggered=true。 */
        data class KILLSWITCH_PROTECTED<T>(override val winner: LwwEnvelope<T>) : LwwMergeResult<T>
    }

    /** 方便 fuse merge 时识别：payload 带 killSwitch1Triggered。 */
    interface HasKillSwitch1 {
        val killSwitch1Triggered: Boolean
    }

    // 让 ZthFuseFirestoreDto 满足 HasKillSwitch1（通过扩展避免修改 @Serializable data class）
    fun withHasKillSwitch(dto: ZthFuseFirestoreDto): HasKillSwitch1 = object : HasKillSwitch1 {
        override val killSwitch1Triggered: Boolean get() = dto.killSwitch1Triggered
    }
}
