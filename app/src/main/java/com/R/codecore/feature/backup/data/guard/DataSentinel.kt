package com.R.codecore.feature.backup.data.guard

import android.content.Context
import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.ChatSessionDao
import com.R.codecore.feature.backup.data.LegacyPackageDetector
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据完整性哨兵：启动时检测"数据是否异常变空"，区分「全新安装 / 正常升级 / 数据丢失 / 包名被改」。
 *
 * 背景：包名（applicationId）变更 = 全新安装，历史对话"消失"；数据被异常清空同样表现为会话数为 0。
 * 哨兵把这两种情况与「正常升级」「全新安装」区分开，供上层提示用户 / 触发自动备份。
 *
 * 关键修正（包名变更可感知）：哨兵自身的记忆（AppRunMeta）存在包名私有目录，包名一变它也跟着丢，
 * 此前会把「rebrand 升级导致的数据隔离」误判为「全新安装」静默通过（用户看到的就是"不报错"）。
 * 现引入 [LegacyPackageDetector]：本包名下无记忆但检测到同签名旧包仍安装 → 判 [SentinelVerdict.PACKAGE_CHANGED]，
 * 从而让"历史数据消失"变成"可感知、可恢复的异常"，不再静默。
 *
 * 失败兜底：任何一步失败都只记日志并按 [SentinelVerdict.NORMAL] 处理，绝不阻断启动。
 */
@Singleton
class DataSentinel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val appRunMeta: AppRunMeta,
    private val chatSessionDao: ChatSessionDao,
    private val legacyPackageDetector: LegacyPackageDetector,
) {
    private companion object {
        const val TAG = "DataSentinel"
    }

    /**
     * 执行一次完整性检查，并据结果持久化运行元数据。
     * 必须在非主线程调用（内部已切 IO）。
     */
    suspend fun check(): SentinelVerdict = withContext(Dispatchers.IO) {
        runCatching {
            val meta = appRunMeta.snapshot()
            val vc = currentVersionCode()
            val pkg = context.packageName
            val count = chatSessionDao.count()
            val verdict = SentinelLogic.evaluate(
                meta = meta,
                currentVersionCode = vc,
                currentApplicationId = pkg,
                sessionCount = count,
                legacyPackageInstalled = legacyPackageDetector.hasSameSignatureLegacyPackage(),
            )
            when (verdict) {
                SentinelVerdict.FIRST_RUN -> appRunMeta.markInitialized(vc, pkg)
                SentinelVerdict.UPGRADED, SentinelVerdict.NORMAL -> appRunMeta.updateLastRun(vc, pkg)
                // DATA_LOST / PACKAGE_CHANGED：不更新 lastRun，保留"疑似丢失"状态供 UI 持续提示；
                // 用户恢复数据后下次运行（会话数>0 且包名一致）会自然回落到 UPGRADED/NORMAL。
                SentinelVerdict.DATA_LOST, SentinelVerdict.PACKAGE_CHANGED -> Unit
            }
            FileLogger.d(TAG, "哨兵判定: $verdict (vc=$vc pkg=$pkg sessions=$count lastVc=${meta.lastVersionCode})")
            verdict
        }.onFailure {
            FileLogger.w(TAG, "数据完整性检查失败，按正常处理", it)
            SentinelVerdict.NORMAL
        }.getOrNull() ?: SentinelVerdict.NORMAL
    }

    private fun currentVersionCode(): Int = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode.toInt()
    }.getOrDefault(0)
}
