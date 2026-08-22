package com.R.codecore.feature.backup.data.guard

/**
 * 哨兵判定结果。
 *
 * - [FIRST_RUN]：本包名首次运行（全新安装 / 首次启动），静默，写初始化标记。
 * - [UPGRADED]：正常升级（versionCode 增大），触发升级前自动备份。
 * - [NORMAL]：同一版本重复运行，正常。
 * - [DATA_LOST]：本包名下已初始化过数据，但当前会话数为 0 —— 数据疑似丢失（如被清空/异常），提示用户。
 * - [PACKAGE_CHANGED]：上次运行的包名与当前不一致 —— 包名被改动（理论上被 D1/D3/CI 门禁拦截，运行时兜底），引导迁移。
 */
enum class SentinelVerdict { FIRST_RUN, UPGRADED, NORMAL, DATA_LOST, PACKAGE_CHANGED }

/**
 * 数据完整性哨兵的**纯判定逻辑**（无 IO、无 Android 依赖，便于单元测试）。
 *
 * 判定顺序：
 * 1. 未初始化 → [FIRST_RUN]
 * 2. 包名变化 → [PACKAGE_CHANGED]（优先于 DATA_LOST：包名变更是"全新安装"，会话数必为 0）
 * 3. 已初始化但会话数 = 0 → [DATA_LOST]
 * 4. versionCode 增大 → [UPGRADED]
 * 5. 其余 → [NORMAL]
 */
object SentinelLogic {

    fun evaluate(
        meta: RunMeta,
        currentVersionCode: Int,
        currentApplicationId: String,
        sessionCount: Int,
    ): SentinelVerdict {
        if (!meta.dataInitialized) return SentinelVerdict.FIRST_RUN
        if (meta.lastApplicationId != null && meta.lastApplicationId != currentApplicationId) {
            return SentinelVerdict.PACKAGE_CHANGED
        }
        if (sessionCount == 0) return SentinelVerdict.DATA_LOST
        if (currentVersionCode > meta.lastVersionCode) return SentinelVerdict.UPGRADED
        return SentinelVerdict.NORMAL
    }
}
