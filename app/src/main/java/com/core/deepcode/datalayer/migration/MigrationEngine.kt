package com.core.deepcode.datalayer.migration

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.engine.DatabasePathProvider
import com.core.deepcode.datalayer.engine.LibName
import java.io.File

/**
 * 迁移引擎（设计 §5：数据保护核心）。
 *
 * 职责：
 *  - **[preOpen]（driver 打开前）**：用 [VersionProbe] 原生只读探测真实 user_version，
 *    在「迁移前」对旧版本库做文件级快照（§5.3 安全网）；版本回退（current > target）提前拒绝。
 *    ⚠️ 必须在 `AndroidSqliteDriver` 构造**之前**调用——driver 构造即打开并执行
 *    create/migrate，届时 user_version 已被改写为 target，再探测就永远进不了快照分支。
 *  - **[ensureSchema]（driver 打开后）**：打开后兜底校验 + 代码迁移（[CodeMigration]）。
 *    版本探测与迁移前快照已前移至 [preOpen]，此处 `currentVersion(driver)` 读到的是迁移后的版本。
 *
 * 失败语义（§5.4）：本引擎不写版本号、保留现场，由调用方（DataLayerModule 打开环节）决定重试/回滚；
 * 连续失败 N 次由上层回滚到 [restoreSnapshot] 产出的快照。
 */
class MigrationEngine(
    private val pathProvider: DatabasePathProvider,
    // 默认探测（非 Android / 测试环境）：不读物理版本、一律视为「已对齐」，
    // 不触发快照。真实 Android 环境由 DataLayerModule 注入 AndroidVersionProbe 覆盖。
    private val probe: VersionProbe = object : VersionProbe {
        override fun readVersion(lib: LibName): Int = 0
    },
) {

    private companion object {
        const val TAG = "MigrationEngine"
    }

    /**
     * driver 构造（打开 / 迁移）**之前**调用：探测真实 user_version 并执行迁移前快照。
     *
     * 必须在 [com.core.deepcode.datalayer.engine.ConnectionPool.driver] 的
     * `factory.create(lib)`（即 `AndroidSqliteDriver` 构造）之前触发，
     * 否则快照永远落在「数据已被迁移」之后，失去回滚意义。
     *
     * @return 本次决策动作（[PreOpenAction]），便于上层日志与测试断言。
     */
    fun preOpen(lib: LibName, schema: SqlSchema<*>, heavy: Boolean = false): PreOpenAction {
        val current = probe.readVersion(lib)
        val target = schema.version
        FileLogger.i(TAG, "preOpen($lib): current=$current target=$target")
        val action = decidePreOpen(current, target)
        when (action) {
            PreOpenAction.FRESH -> {
                FileLogger.i(TAG, "  $lib 全新库（current=0），待 driver 打开时 onCreate 建表，无需快照")
            }
            PreOpenAction.UPGRADE_SNAPSHOT -> {
                FileLogger.i(TAG, "  $lib 旧版本 $current → $target，driver 打开(迁移)前先快照保命")
                snapshot(lib, heavy)
            }
            PreOpenAction.ALIGNED_NOOP -> {
                FileLogger.i(TAG, "  $lib 版本已对齐（current==target==$current），无需快照；结构完整性由 onOpened 的 SchemaSelfHealer 保证")
            }
            PreOpenAction.DOWNGRADE -> {
                // 提前到打开前拒绝，避免用低版本 schema 打开高版本数据造成损坏。
                error("[$lib] 检测到版本回退：$current > $target，拒绝打开以防数据损坏")
            }
        }
        return action
    }

    fun ensureSchema(
        lib: LibName,
        driver: SqlDriver,
        schema: SqlSchema<*>,
        codeMigrations: List<CodeMigration> = emptyList(),
        sqlMigrations: Array<out AfterVersion> = emptyArray(),
        heavy: Boolean = false,
    ) {
        // ⚠️ driver 已在 factory.create 阶段打开并执行过 onCreate/onUpgrade（schema.create/migrate），
        //    此处 currentVersion(driver) 读到的是「迁移后」的 user_version。
        //    版本探测 + 迁移前快照已前移至 [preOpen]（在 factory.create 之前调用）。
        //    本方法现在只负责：打开后兜底校验 + codeMigrations + 日志。
        val current = currentVersion(driver)
        val target = schema.version
        FileLogger.i(TAG, "ensureSchema($lib): current=$current target=$target（打开后兜底）")
        when {
            current.toLong() == target -> {
                // 显式 no-op。⚠️ 版本相等 ≠ 表结构一致（v0.5.0-rc1 事故）：结构演进未同步新增 .sqm 时
                // user_version 不变但表缺列，结构完整性由 ConnectionPool.onOpened 里的 SchemaSelfHealer 保证。
                FileLogger.i(TAG, "  $lib 版本已对齐（current==target==$current），no-op；结构完整性由 SchemaSelfHealer 保证")
            }
            current.toLong() < target -> {
                // 正常不该进入（preOpen 已先快照并让 driver 完成迁移）；
                // 若因某种原因 preOpen 未跑而 driver 仍完成了升级，这里补执行 codeMigrations 兜底。
                FileLogger.w(TAG, "  $lib 打开后 current($current) < target($target)：preOpen 可能未执行，补跑 codeMigrations")
                codeMigrations
                    .filter { it.from >= current && it.to <= target }
                    .sortedBy { it.from }
                    .forEach { it.block(driver) }
            }
            else -> error("[$lib] 检测到版本回退：$current > $target，拒绝打开以防数据损坏")
        }
    }

    /** 读取 PRAGMA user_version（每库单一版本真相，§5.6）。 */
    fun currentVersion(driver: SqlDriver): Int {
        return driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            // SQLDelight 2.x：SqlCursor.next() 返回 QueryResult<Boolean>，经 .value 解包；
            // executeQuery 的 map 需返回 QueryResult<R>（非裸值）。
            val v = if (cursor.next().value) cursor.getLong(0)?.toInt() ?: 0 else 0
            QueryResult.Value(v)
        }, 0, null).value
    }

    /** 迁移前文件级快照（§5.3）：cp 主库 + -wal + -shm 到 backup/<name>.bak（零逻辑、保真）。 */
    fun snapshot(lib: LibName, heavy: Boolean) {
        val main = pathProvider.mainDb(lib)
        if (!main.exists()) return
        val bak = pathProvider.snapshotFile(lib)
        main.copyTo(bak, overwrite = true)
        copySidecar(main, bak, "wal")
        copySidecar(main, bak, "shm")
        if (heavy) {
            // 重版本/危险迁移：此处叠加逻辑备份（SQL dump / 表级导出），当前留扩展位。
        }
    }

    /** 回滚到最近一次快照（§5.4：连续失败 N 次后调用）。 */
    fun restoreSnapshot(lib: LibName): Boolean {
        val main = pathProvider.mainDb(lib)
        val bak = pathProvider.snapshotFile(lib)
        if (!bak.exists()) return false
        // 快照 → 主库（方向不可反：反了会用损坏的主库覆盖掉唯一的安全网快照）
        bak.copyTo(main, overwrite = true)
        restoreSidecar(bak, main, "wal")
        restoreSidecar(bak, main, "shm")
        // 主库已被快照内容替换，原 -wal 属于旧内容，必须清掉避免与新主库不一致
        main.resolveSibling("${main.name}-wal").takeIf { it.exists() && !bak.resolveSibling("${bak.name}-wal").exists() }?.delete()
        return true
    }

    private fun copySidecar(main: File, bak: File, ext: String) {
        val src = main.resolveSibling("${main.name}-$ext")
        if (src.exists()) src.copyTo(bak.resolveSibling("${bak.name}-$ext"), overwrite = true)
    }

    private fun restoreSidecar(bak: File, main: File, ext: String) {
        val src = bak.resolveSibling("${bak.name}-$ext")
        if (src.exists()) src.copyTo(main.resolveSibling("${main.name}-$ext"), overwrite = true)
    }
}
