package com.R.codecore.feature.settings.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.R.codecore.feature.settings.data.local.dao.AIProviderDao
import com.R.codecore.feature.settings.data.local.entity.AIProviderEntity

/**
 * 数据层重构（新写法）后的 settings 域独立库（v1 全新）。
 *
 * 拆分自旧单巨库 [com.R.codecore.feature.agent.data.local.database.LegacyAgentDatabase]（v49）。
 * 仅承载 settings 域（AIProvider），与其他域库完全解耦。旧库数据由移植逻辑一次性搬入。
 */
@Database(
    entities = [
        AIProviderEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class SettingsDatabase : RoomDatabase() {
    abstract fun aiProviderDao(): AIProviderDao

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
