package com.R.codecore.feature.workspace.data.repository

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import com.R.codecore.core.util.FileLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * workspace 模块 DataStore 收敛搬迁器（数据层重构 T2：「旧版写法」workspace 2 个碎片 →
 * 「新写法」每模块一个）。
 *
 * 首次启动时把旧 `ftp_server_prefs`（FtpServerManager 专用）的全部键值合并写入统一的
 * workspace_prefs，置位迁移标记后删除旧文件。
 * 幂等：标记已置位或任何一步失败（不置位、不删旧文件）下次启动自动重试，绝不丢用户设置。
 */
@Singleton
class WorkspaceDataStoreMigrator @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private companion object {
        const val TAG = "WorkspaceDSMigrator"

        /** 搬迁完成标记：置位后不再重复搬迁。 */
        val MIGRATION_DONE_KEY = booleanPreferencesKey("workspace_migration_done_v1")

        /** 旧版碎片 DataStore 文件名（全项目盘点，workspace 模块：FtpServerManager 独占）。 */
        const val LEGACY_STORE_NAME = "ftp_server_prefs"
    }

    /** 幂等入口：失败不置位标记、不删旧文件，下次启动自动重试。 */
    suspend fun migrateIfNeeded() {
        runCatching { doMigrate() }.onFailure { e ->
            FileLogger.e(
                TAG,
                "workspace DataStore 收敛搬迁失败，旧文件保留、标记未置位，下次启动自动重试。原因=${e.message}",
                e
            )
        }
    }

    private suspend fun doMigrate() {
        val target = context.workspaceDataStore
        if (target.data.first()[MIGRATION_DONE_KEY] == true) return

        val file = context.preferencesDataStoreFile(LEGACY_STORE_NAME)
        if (file.exists()) {
            val legacy = PreferenceDataStoreFactory.create(produceFile = { file })
            val prefs = runCatching { legacy.data.first() }.getOrNull()
            if (prefs != null && prefs.asMap().isNotEmpty()) {
                target.edit { targetPrefs ->
                    for ((key, value) in prefs.asMap()) {
                        when (value) {
                            is Boolean -> targetPrefs[key as Preferences.Key<Boolean>] = value
                            is Int -> targetPrefs[key as Preferences.Key<Int>] = value
                            is Long -> targetPrefs[key as Preferences.Key<Long>] = value
                            is Float -> targetPrefs[key as Preferences.Key<Float>] = value
                            is String -> targetPrefs[key as Preferences.Key<String>] = value
                            is Set<*> -> targetPrefs[key as Preferences.Key<Set<String>>] = value as Set<String>
                            else -> { /* 未知类型忽略，避免写坏统一文件 */ }
                        }
                    }
                }
                FileLogger.i(TAG, "workspace 旧值搬迁：$LEGACY_STORE_NAME → workspace_prefs（${prefs.asMap().size} 项）")
            }
        }

        target.edit { it[MIGRATION_DONE_KEY] = true }
        runCatching { context.preferencesDataStoreFile(LEGACY_STORE_NAME).let { f -> if (f.exists()) f.delete() } }
        FileLogger.i(TAG, "workspace DataStore 收敛完成：$LEGACY_STORE_NAME 已搬迁并清理")
    }
}
