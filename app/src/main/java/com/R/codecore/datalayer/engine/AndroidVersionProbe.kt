package com.R.codecore.datalayer.engine

import android.database.sqlite.SQLiteDatabase
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.migration.VersionProbe

/**
 * [VersionProbe] 的 Android 实现：用原生 [SQLiteDatabase] 只读打开库文件读 `user_version`。
 *
 * 只读打开不会触发 SQLDelight 的 `onCreate` / `onUpgrade`，因此读到的是库当前的真实版本，
 * 而非迁移后的目标版本。见 [VersionProbe] 文档说明。
 */
class AndroidVersionProbe(private val pathProvider: DatabasePathProvider) : VersionProbe {

    override fun readVersion(lib: LibName): Int {
        val file = pathProvider.mainDb(lib)
        if (!file.exists()) {
            FileLogger.i(TAG, "readVersion($lib): 文件不存在 → 全新库(0)")
            return 0
        }
        return try {
            SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                db.rawQuery("PRAGMA user_version", null).use { c ->
                    if (c.moveToFirst()) c.getInt(0) else 0
                }
            }
        } catch (e: Exception) {
            // 只读打开失败：库可能损坏 / 不可读。按 0（全新库）处理，
            // 交给 ensureSchema + SchemaSelfHealer 走「建表 / 无损重建」兜底，而不是在这里放弃。
            FileLogger.e(TAG, "readVersion($lib) 只读打开失败（可能损坏），按 0 处理交由自愈兜底", e)
            0
        }
    }

    companion object {
        private const val TAG = "AndroidVersionProbe"
    }
}
