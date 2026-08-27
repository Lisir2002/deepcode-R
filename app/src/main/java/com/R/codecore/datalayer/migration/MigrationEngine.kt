package com.R.codecore.datalayer.migration

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import com.R.codecore.datalayer.engine.DatabasePathProvider
import com.R.codecore.datalayer.engine.LibName
import java.io.File

/**
 * 迁移引擎（设计 §5：数据保护核心）。
 *
 * 职责（每个库打开时调用一次 [ensureSchema]）：
 *  - 全新库（user_version=0）：[SqlSchema.create] 建表并落地版本号。
 *  - 旧版本：先文件级快照（§5.3 安全网）→ [SqlSchema.migrate]（.sqm DDL 链）→ 代码迁移（[CodeMigration]）。
 *  - 版本回退（current > target）：拒绝打开，防数据损坏。
 *
 * 失败语义（§5.4）：本引擎不写版本号、保留现场，由调用方（DataLayerModule 打开环节）决定重试/回滚；
 * 连续失败 N 次由上层回滚到 [restoreSnapshot] 产出的快照。
 */
class MigrationEngine(private val pathProvider: DatabasePathProvider) {

    fun ensureSchema(
        lib: LibName,
        driver: SqlDriver,
        schema: SqlSchema<*>,
        codeMigrations: List<CodeMigration> = emptyList(),
        sqlMigrations: Array<out AfterVersion> = emptyArray(),
        heavy: Boolean = false,
    ) {
        val current = currentVersion(driver)
        val target = schema.version
        when {
            current == 0 -> schema.create(driver)
            current < target -> {
                snapshot(lib, heavy)
                schema.migrate(driver, current.toLong(), target, *sqlMigrations)
                codeMigrations
                    .filter { it.from >= current && it.to <= target }
                    .sortedBy { it.from }
                    .forEach { it.block(driver) }
            }
            current > target -> error("[$lib] 检测到版本回退：$current > $target，拒绝打开以防数据损坏")
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
