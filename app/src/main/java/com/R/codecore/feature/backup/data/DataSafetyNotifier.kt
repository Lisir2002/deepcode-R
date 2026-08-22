package com.R.codecore.feature.backup.data

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.backup.data.guard.DataSentinel
import com.R.codecore.feature.backup.data.guard.SentinelVerdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 数据保全通知器：启动检查的**唯一出口**，把哨兵判定结果发布给 UI（启动级全局告警弹窗）。
 *
 * 背景：此前哨兵只在 AIEditorApp 后台跑一次，判定为「数据疑似丢失 / 包名变更」时只在
 * 备份设置页内显示横幅，用户冷启动后根本看不到——这正是「安装更新软件后历史数据消失，却
 * 不报错」的原因之一。本通知器把判定结果以 [StateFlow] 对外发布，MainActivity 观察
 * [verdict] 并在需要时弹出**启动级全局告警**（见 DataSafetyStartupAlert），让数据异常在
 * 用户打开 App 的第一眼就被看见、可恢复。
 *
 * 职责边界：
 * - 只负责「跑哨兵 + 升级前自动备份 + 发布判定结果」，不持有 UI 状态；
 * - [dismissStartupAlert] 仅对本次进程会话生效（重启后若仍未恢复会再次提示，符合
 *   「保留告警态直至数据恢复」的哨兵语义）；
 * - 任何失败仅记日志，绝不阻断启动。
 */
@Singleton
class DataSafetyNotifier @Inject constructor(
    private val dataSentinel: DataSentinel,
    private val autoBackupManager: AutoBackupManager,
) {
    private companion object {
        const val TAG = "DataSafetyNotifier"
    }

    private val _verdict = MutableStateFlow<SentinelVerdict?>(null)

    /** 最近一次哨兵判定结果；null 表示尚未检查。MainActivity 观察此值决定是否弹启动告警。 */
    val verdict: StateFlow<SentinelVerdict?> = _verdict.asStateFlow()

    @Volatile
    private var dismissedForSession = false

    /** 当前是否需要弹启动级全局告警：判定为数据丢失/包名变更，且本会话用户尚未忽略。 */
    val shouldShowStartupAlert: Boolean
        get() = !dismissedForSession && (
            _verdict.value == SentinelVerdict.DATA_LOST ||
                _verdict.value == SentinelVerdict.PACKAGE_CHANGED
            )

    /** 用户选择「我知道了」：本次会话不再弹；下次冷启动若仍未恢复会再次评估。 */
    fun dismissStartupAlert() {
        dismissedForSession = true
    }

    /**
     * 启动时执行一次数据保全检查（必须非主线程调用，内部已切 IO）：
     * 1. 跑哨兵（区分全新安装/正常升级/数据丢失/包名变更）；
     * 2. 判定为正常升级时自动双保险备份（本机私有 + 外部公共目录，见 [AutoBackupManager.backupAll]）；
     * 3. 发布判定结果供 UI 消费。
     * 任何失败仅记日志，绝不阻断启动。
     */
    suspend fun run() {
        val v = runCatching { dataSentinel.check() }
            .onFailure { FileLogger.w(TAG, "数据保全检查失败，静默（不影响启动）", it) }
            .getOrNull() ?: return
        if (v == SentinelVerdict.UPGRADED) {
            runCatching { autoBackupManager.backupAll() }
                .onFailure { FileLogger.w(TAG, "升级前自动备份失败（不影响启动）", it) }
        }
        _verdict.value = v
        FileLogger.d(TAG, "启动数据保全完成: $v")
    }
}
