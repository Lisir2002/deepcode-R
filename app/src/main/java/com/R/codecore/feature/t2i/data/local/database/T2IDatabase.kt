package com.R.codecore.feature.t2i.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderDao
import com.R.codecore.feature.t2i.data.local.dao.T2IProviderModelDao
import com.R.codecore.feature.t2i.data.local.dao.T2ITaskDao
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderEntity
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderModelEntity
import com.R.codecore.feature.t2i.data.local.entity.T2ITaskEntity

/**
 * 数据层重构（新写法）后的 t2i 域独立库（v1 全新）。
 *
 * 拆分自旧单巨库 [com.R.codecore.feature.agent.data.local.database.LegacyAgentDatabase]（v49）。
 * 仅承载 t2i 域（T2IProvider / T2IProviderModel / T2ITask），与其他域库完全解耦。
 * 旧库数据由移植逻辑一次性搬入。
 */
@Database(
    entities = [
        T2IProviderEntity::class,
        T2IProviderModelEntity::class,
        T2ITaskEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class T2IDatabase : RoomDatabase() {
    abstract fun t2iProviderDao(): T2IProviderDao
    abstract fun t2iProviderModelDao(): T2IProviderModelDao
    abstract fun t2iTaskDao(): T2ITaskDao

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
