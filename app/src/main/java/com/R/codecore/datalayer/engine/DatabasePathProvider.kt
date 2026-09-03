package com.R.codecore.datalayer.engine

import android.content.Context
import java.io.File

/**
 * 6 个库的物理路径解析（设计 §12.3）。
 *
 * 硬性保护意图：所有库放在 App 内部存储 /data/data/<pkg>/files/ 之下，
 * 普通用户不可见、不可删，需 root 才能访问（防误删）。
 *
 * ⚠️ 严禁解析到外部/共享存储（getExternalFilesDir / scoped storage / SD 卡）。
 *    任何新库路径必须经本 Provider，杜绝误指到用户可访问空间。
 *
 * v2-full-takeover P0-3：抽为接口，平台实现见 [AndroidDatabasePathProvider]，
 * 使 [com.R.codecore.datalayer.migration.MigrationEngine] 的快照/回滚可在 JVM 单测覆盖。
 */
/**
 * 库文件名是**数据契约（data contract）**，不可随意手改。
 *
 * ⚠️ 改 `fileName` 后缀（如 v2 → v3）= 放弃旧文件名指向的物理文件 = **清空该库的全部历史数据**
 * （App 之后只会去读新文件名，旧文件不再被打开）。rc7 曾因误判 `no such column: agent_message.id`
 * 根因（实为 SQL 形态不合法，非库损坏）而把 agent 库改名 `rcodecore_agent_v3.db`，
 * 导致所有历史会话被静默清空——这是一次过度反应，代价不可逆。
 *
 * 纪律（固化，防复发）：
 *  1. 表结构演进 / 缺列等问题，**一律走 [SchemaSelfHealer] 无损重建或 .sqm 迁移**，绝不用「改文件名换库」规避；
 *  2. 仅当库文件确属**不可自愈的损坏**（如 `SQLITE_CORRUPT`、只读打开即失败）时，才考虑换文件名重建，
 *     且必须在 changelog 显式记录「为何弃旧库」并保留旧文件可人工恢复；
 *  3. 任何 `fileName` 变更都是破坏性数据事件，需经评审，不在修复提交里顺手改。
 */
enum class LibName(val fileName: String) {
    AGENT("rcodecore_agent_v3.db"),
    CREDENTIALS("rcodecore_credentials_v2.db"),
    SETTINGS("rcodecore_settings_v2.db"),
    WORKSPACE("rcodecore_workspace_v2.db"),
    T2I("rcodecore_t2i_v2.db"),
    INFRA("rcodecore_infra_v2.db"),
}

interface DatabasePathProvider {

    /** 主库路径。 */
    fun mainDb(lib: LibName): File

    /** 备份目录（快照落点所在目录）。 */
    fun backupDir(): File

    /** 迁移前文件级快照落点（设计 §5.3）。 */
    fun snapshotFile(lib: LibName): File = backupDir().resolve("${lib.fileName}.bak")
}

/**
 * Android 实现：主库经系统 getDatabasePath 落到 files/databases/<fileName>
 * （与 AndroidSqliteDriver 实际落点一致）；快照目录 files/backup/ 与主库平级，
 * 便于整目录排除 Android 云备份。
 */
class AndroidDatabasePathProvider(private val context: Context) : DatabasePathProvider {

    override fun mainDb(lib: LibName): File = context.getDatabasePath(lib.fileName)

    override fun backupDir(): File = context.getFilesDir().resolve("backup").also { it.mkdirs() }
}
