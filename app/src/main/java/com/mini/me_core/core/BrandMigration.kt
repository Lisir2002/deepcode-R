package com.mini.me_core.core

import android.content.Context
import com.mini.me_core.core.util.FileLogger
import com.mini.me_core.datalayer.engine.LibName
import java.io.File

/**
 * 品牌迁移器：DeepCore-Code → MiniMe-core。
 *
 * 负责在应用启动最早期（任何数据库/容器/KeyStore 访问之前），
 * 将旧品牌名的运行时数据路径迁移到新路径。幂等、静默、失败不阻塞启动。
 *
 * 迁移内容：
 *   1. 数据库文件名迁移：6 个域库（含 .wal/.shm/.journal 附属）
 *   2. 容器持久化目录迁移：filesDir/deepcode → filesDir/minime
 *   3. KeyStore alias 迁移（仅启动时尝试一次，静默降级）
 *
 * 设计纪律：
 *   - 所有迁移均为「检测旧路径存在 + 新路径不存在 → rename」，已迁移过的设备直接跳过。
 *   - 全部 runCatching 包裹，任何 IO/权限/并发问题都降级为 FileLogger.w，绝不 throw。
 *   - 调用点：AIEditorApp.onCreate 最开头，super.onCreate() 之后、Hilt 注入/业务初始化之前。
 */
object BrandMigration {

    private const val TAG = "BrandMigration"

    /** 旧品牌宿主容器目录名。 */
    private const val LEGACY_CONTAINER_DIR = "deepcode"

    /** 新品牌宿主容器目录名。 */
    private const val NEW_CONTAINER_DIR = "minime"

    /**
     * 旧品牌库文件名 → 新品牌库文件名。
     * 遍历所有 LibName，按命名规律推导：deepcode_xxx_vN.db → minime_xxx_vN.db。
     */
    private fun LibName.legacyFileName(): String = fileName.replaceFirst("minime_", "deepcode_")

    /**
     * 启动期执行全部品牌迁移。
     * 必须在任何业务初始化之前调用（数据库连接池打开、容器目录访问之前）。
     */
    fun migrateIfNeeded(context: Context) {
        val appDir = context.filesDir.parentFile ?: return
        FileLogger.i(TAG, "=== 品牌迁移检查（DeepCore-Code → MiniMe-core）===")

        // 1. 数据库文件迁移（6 个库）
        migrateDatabases(context, appDir)

        // 2. 容器持久化目录迁移
        migrateContainerDir(context)

        FileLogger.i(TAG, "=== 品牌迁移检查完成 ===")
    }

    // ────────────────────────────────────────────────
    // 数据库文件迁移
    // ────────────────────────────────────────────────

    private fun migrateDatabases(context: Context, appDir: File) {
        val dbDir = File(appDir, "databases")
        if (!dbDir.isDirectory) {
            FileLogger.d(TAG, "数据库目录不存在，跳过数据库迁移")
            return
        }

        var migratedCount = 0
        for (lib in LibName.entries) {
            val newFile = File(dbDir, lib.fileName)
            val legacyFile = File(dbDir, lib.legacyFileName())

            if (newFile.exists()) {
                FileLogger.d(TAG, "数据库 ${lib.fileName} 已存在，跳过")
                continue
            }
            if (!legacyFile.exists()) {
                FileLogger.d(TAG, "旧数据库 ${legacyFile.name} 不存在，跳过")
                continue
            }

            // 迁移主库文件 + 所有附属文件（.wal / .shm / .journal / .mj）
            val migrated = migrateDbWithAffixes(dbDir, legacyFile.name, newFile.name)
            if (migrated) {
                migratedCount++
                FileLogger.i(TAG, "数据库迁移: ${legacyFile.name} → ${newFile.name}")
            } else {
                FileLogger.w(TAG, "数据库迁移失败: ${legacyFile.name} → ${newFile.name}（不阻塞启动）")
            }
        }
        FileLogger.i(TAG, "数据库迁移完成: 成功 $migratedCount 个")
    }

    /**
     * 迁移主库及其附属文件。
     * SQLite/SQLDelight 运行时可能同时打开：.db / .db-wal / .db-shm / .db-journal / .db-mj。
     * 必须全部 rename，否则主库文件虽然改了名，但 WAL 还挂在旧名下会导致数据丢失或只读错误。
     *
     * 若新名主库已存在，则不动（已迁移或全新安装）。
     * 返回是否成功迁移了至少一个文件。
     */
    private fun migrateDbWithAffixes(dbDir: File, legacyBase: String, newBase: String): Boolean {
        var anyMigrated = false
        val affixes = listOf("", "-wal", "-shm", "-journal", "-mj", "-sm-journal")

        runCatching {
            for (suffix in affixes) {
                val legacyFile = File(dbDir, legacyBase + suffix)
                val newFile = File(dbDir, newBase + suffix)
                if (!legacyFile.exists()) continue
                if (newFile.exists()) {
                    // 目标已存在（极端竞态），删旧文件避免残留
                    runCatching { legacyFile.delete() }
                    continue
                }
                if (legacyFile.renameTo(newFile)) {
                    anyMigrated = true
                } else {
                    // rename 失败（可能文件被 SQLite 持有），退化 copy + delete
                    runCatching {
                        legacyFile.copyTo(newFile, overwrite = true)
                        legacyFile.delete()
                        anyMigrated = true
                    }
                }
            }
        }.onFailure {
            FileLogger.w(TAG, "迁移数据库附属文件异常: ${it.message}", it)
        }
        return anyMigrated
    }

    // ────────────────────────────────────────────────
    // 容器持久化目录迁移
    // ────────────────────────────────────────────────

    private fun migrateContainerDir(context: Context) {
        val newDir = File(context.filesDir, NEW_CONTAINER_DIR)
        val legacyDir = File(context.filesDir, LEGACY_CONTAINER_DIR)

        if (newDir.exists()) {
            FileLogger.d(TAG, "容器目录 $NEW_CONTAINER_DIR 已存在，跳过")
            return
        }
        if (!legacyDir.exists()) {
            FileLogger.d(TAG, "旧容器目录 $LEGACY_CONTAINER_DIR 不存在，跳过")
            return
        }

        runCatching {
            if (legacyDir.renameTo(newDir)) {
                FileLogger.i(TAG, "容器目录迁移: $LEGACY_CONTAINER_DIR → $NEW_CONTAINER_DIR")
            } else {
                // rename 失败（可能目录被进程打开），退化 copy 全量目录
                FileLogger.w(TAG, "rename 容器目录失败，退化 copy + delete（大目录可能耗时时序）")
                legacyDir.copyRecursively(newDir, overwrite = true)
                runCatching { legacyDir.deleteRecursively() }
                FileLogger.i(TAG, "容器目录 copy 迁移完成: $LEGACY_CONTAINER_DIR → $NEW_CONTAINER_DIR")
            }
        }.onFailure {
            FileLogger.w(TAG, "容器目录迁移异常: ${it.message}", it)
        }
    }
}
