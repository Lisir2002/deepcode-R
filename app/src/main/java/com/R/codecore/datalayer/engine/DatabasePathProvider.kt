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
