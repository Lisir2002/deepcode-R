package com.R.codecore.feature.credentials.data.local.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.R.codecore.feature.credentials.data.local.dao.GitCredentialDao
import com.R.codecore.feature.credentials.data.local.entity.GitCredentialEntity

/**
 * 数据层重构（新写法）后的 credentials 域独立库（v1 全新）。
 *
 * 拆分自旧单巨库 [com.R.codecore.feature.agent.data.local.database.LegacyAgentDatabase]（v49）。
 * 仅承载 credentials 域（GitCredential），与其他域库完全解耦。旧库数据由移植逻辑一次性搬入。
 */
@Database(
    entities = [
        GitCredentialEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class CredentialsDatabase : RoomDatabase() {
    abstract fun gitCredentialDao(): GitCredentialDao

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
