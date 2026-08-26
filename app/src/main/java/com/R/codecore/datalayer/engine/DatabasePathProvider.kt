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
 */
enum class LibName(val fileName: String) {
    AGENT("rcodecore_agent_v2.db"),
    CREDENTIALS("rcodecore_credentials_v2.db"),
    SETTINGS("rcodecore_settings_v2.db"),
    WORKSPACE("rcodecore_workspace_v2.db"),
    T2I("rcodecore_t2i_v2.db"),
    INFRA("rcodecore_infra_v2.db"),
}

class DatabasePathProvider(private val context: Context) {

    /** 主库路径：经系统 getDatabasePath 落到 files/databases/<fileName>（与 AndroidSqliteDriver 实际落点一致）。 */
    fun mainDb(lib: LibName): File = context.getDatabasePath(lib.fileName)

    /** 备份目录：与主库平级的兄弟级 files/backup/，便于整目录排除 Android 云备份。 */
    fun backupDir(): File = context.getFilesDir().resolve("backup").also { it.mkdirs() }

    /** 迁移前文件级快照落点（设计 §5.3）。 */
    fun snapshotFile(lib: LibName): File = backupDir().resolve("${lib.fileName}.bak")
}
