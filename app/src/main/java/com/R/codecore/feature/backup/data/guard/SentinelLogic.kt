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
 * 1. 未初始化 && 无同签名旧包 → [FIRST_RUN]（真正全新安装，静默）
 * 2. 未初始化 && 有同签名旧包 → [PACKAGE_CHANGED]（rebrand 升级：本包名下无记忆、旧包仍安装，
 *    历史数据在旧包私有目录，需引导「旧包导出 → 新包导入」迁移）
 * 3. 上次包名与当前不一致 → [PACKAGE_CHANGED]（优先于 DATA_LOST：包名变更是"全新安装"，会话数必为 0）
 * 4. 已初始化但会话数 = 0 → [DATA_LOST]
 * 5. versionCode 增大 → [UPGRADED]
 * 6. 其余 → [NORMAL]
 */
object SentinelLogic {

    fun evaluate(
        meta: RunMeta,
        currentVersionCode: Int,
        currentApplicationId: String,
        sessionCount: Int,
        legacyPackageInstalled: Boolean = false,
    ): SentinelVerdict {
        if (!meta.dataInitialized) {
            // 本包名下无记忆：同签名旧包仍安装说明是 rebrand 升级（数据在旧包目录），须提示迁移而非静默；
            // 否则才是真正全新安装。
            return if (legacyPackageInstalled) SentinelVerdict.PACKAGE_CHANGED else SentinelVerdict.FIRST_RUN
        }
        if (meta.lastApplicationId != null && meta.lastApplicationId != currentApplicationId) {
            return SentinelVerdict.PACKAGE_CHANGED
        }
        if (sessionCount == 0) return SentinelVerdict.DATA_LOST
        if (currentVersionCode > meta.lastVersionCode) return SentinelVerdict.UPGRADED
        return SentinelVerdict.NORMAL
    }
}
