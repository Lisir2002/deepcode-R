package com.core.deepcode.datalayer.migration

import com.core.deepcode.datalayer.engine.LibName

/**
 * 在 SQLDelight driver「构造 / 打开 / 迁移」之前，读取库的物理 `user_version`。
 *
 * ## 为什么必须绕开 SQLDelight driver 探测
 * `app.cash.sqldelight.driver.android.AndroidSqliteDriver` 在**构造时**就打开数据库，
 * 并经由 `SQLiteOpenHelper.onCreate` / `onUpgrade` 回调同步执行 `schema.create` /
 * `schema.migrate`，写入**目标** `user_version`。因此一旦经由 driver 读取 `user_version`，
 * 拿到的永远是「迁移后」的版本：
 *   - 全新库：`create` 已将 `user_version` 设为 target → 读到 target，而非 0；
 *   - 旧版本库：`onUpgrade` 已将 `user_version` 设为 target → 读到 target，而非旧版本号。
 * 后果：[MigrationEngine.ensureSchema] 的 `current == 0` 与 `current < target` 两个分支
 * 永远进不去，本应在「迁移前」执行的文件级快照安全网（§5.3）从未被调用——
 * 这是 rc 演进中长期被忽略的隐性数据风险（迁移若中途失败，无快照可回滚）。
 *
 * ## 解决
 * 在 `AndroidSqliteDriver` 构造**之前**，用原生只读连接探测真实 `user_version`；
 * 仅当 `0 < current < target`（真·旧版本待迁移）时才在「数据还是迁移前状态」时做快照。
 *
 * 该接口把平台差异抽离：Android 侧用 [android.database.sqlite.SQLiteDatabase] 实现；
 * JVM 单测用一个不依赖 framework 的等价实现注入，使 preOpen 决策可被纯 JVM 覆盖。
 */
interface VersionProbe {
    /** 库文件不存在 → 0（全新库）；只读打开失败（可能损坏）→ 记日志后返回 0，交由自愈/重建兜底。 */
    fun readVersion(lib: LibName): Int
}

/**
 * 迁移前决策（纯函数，零平台依赖，便于 JVM 单测）。
 * 对应 [MigrationEngine.preOpen] 在 driver 打开前的分支选择。
 *
 * 注意 [target] 为 `Long`（`SqlSchema.version` 类型），与 `current: Int` 比较时统一转 Long，
 * 避免 Kotlin 跨数值类型的 `==`/`/` 比较报错。
 */
enum class PreOpenAction {
    /** current == 0：全新库，`create` 会建表，无需快照。 */
    FRESH,

    /** 0 < current < target：旧版本待迁移，此刻文件仍是迁移前状态，必须先快照保命。 */
    UPGRADE_SNAPSHOT,

    /** current == target：已对齐，无需快照（结构完整性由 SchemaSelfHealer 保证）。 */
    ALIGNED_NOOP,

    /** current > target：版本回退，拒绝打开以防数据损坏。 */
    DOWNGRADE,
}

fun decidePreOpen(current: Int, target: Long): PreOpenAction {
    val t = current.toLong()
    return when {
        current == 0 -> PreOpenAction.FRESH
        t < target -> PreOpenAction.UPGRADE_SNAPSHOT
        t == target -> PreOpenAction.ALIGNED_NOOP
        else -> PreOpenAction.DOWNGRADE
    }
}
