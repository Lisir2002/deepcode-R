package com.R.codecore.datalayer.repository

import com.R.codecore.datalayer.sqldelight.SettingsDb
import com.R.codecore.datalayer.sqldelight.settings.Settings_pref
import com.R.codecore.datalayer.sqldelight.settings.Settings_profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * settings 域 Repository（设计 §11.3 / L2）：档案 + 强类型偏好表门面。
 * 通用 KV（非强类型偏好）由 infra KVStore 承接，settings 域只保留 profile/pref 强类型表。
 */
class SettingsRepository(private val db: SettingsDb) {

    private val q get() = db.settingsQueries

    suspend fun createProfile(id: String, name: String?, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) { q.insertProfile(id, name, 0, now, now) }

    /** 激活档案：先清全部 active，再置指定档案（单事务保证原子性）。 */
    suspend fun setActiveProfile(id: String, now: Long = System.currentTimeMillis()) =
        withContext(Dispatchers.IO) {
            db.transaction {
                q.clearActiveProfile()
                q.setActiveProfile(1, id)
            }
        }

    suspend fun listProfiles(): List<Settings_profile> =
        withContext(Dispatchers.IO) { q.selectAllProfiles().executeAsList() }

    suspend fun upsertPref(
        id: String, profileId: String?, key: String, type: String,
        stringVal: String?, intVal: Long?, boolVal: Long?, jsonVal: String?,
        now: Long = System.currentTimeMillis(),
    ) = withContext(Dispatchers.IO) {
        q.upsertPref(id, profileId, key, type, stringVal, intVal, boolVal, jsonVal, now)
    }

    suspend fun getPref(profileId: String?, key: String): Settings_pref? =
        withContext(Dispatchers.IO) { q.selectPref(profileId, key).executeAsOneOrNull() }

    suspend fun listPrefs(profileId: String?): List<Settings_pref> =
        withContext(Dispatchers.IO) { q.selectAllPrefs(profileId).executeAsList() }

    suspend fun deletePref(profileId: String?, key: String) =
        withContext(Dispatchers.IO) { q.deletePref(profileId, key) }

    // ── 阶段 1 补表方法（ai_providers）──

    suspend fun insertProvider(
        id: String, name: String, type: String, encryptedApiKey: String, baseUrl: String,
        defaultModel: String, isActive: Long, models: String, isEnabled: Long,
        useFullUrl: Long, useResponseApi: Long,
    ) = withContext(Dispatchers.IO) {
        q.insertProvider(id, name, type, encryptedApiKey, baseUrl, defaultModel, isActive, models, isEnabled, useFullUrl, useResponseApi)
    }

    suspend fun getProvider(id: String): com.R.codecore.datalayer.sqldelight.settings.Ai_providers? =
        withContext(Dispatchers.IO) { q.selectProviderById(id).executeAsOneOrNull() }

    suspend fun listProviders(): List<com.R.codecore.datalayer.sqldelight.settings.Ai_providers> =
        withContext(Dispatchers.IO) { q.selectAllProviders().executeAsList() }

    suspend fun getActiveProvider(): com.R.codecore.datalayer.sqldelight.settings.Ai_providers? =
        withContext(Dispatchers.IO) { q.selectActiveProvider().executeAsOneOrNull() }

    suspend fun deactivateAllProviders() =
        withContext(Dispatchers.IO) { q.deactivateAllProviders() }

    suspend fun deleteProvider(id: String) =
        withContext(Dispatchers.IO) { q.deleteProvider(id) }
}
