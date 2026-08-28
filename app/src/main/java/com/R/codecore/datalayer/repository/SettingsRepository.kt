package com.R.codecore.datalayer.repository

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.R.codecore.datalayer.sqldelight.SettingsDb
import com.R.codecore.datalayer.sqldelight.settings.Ai_providers
import com.R.codecore.datalayer.sqldelight.settings.Settings_pref
import com.R.codecore.datalayer.sqldelight.settings.Settings_profile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * settings 域 Repository（设计 §11.3 / L2）：档案 + 强类型偏好表门面。
 * 通用 KV（非强类型偏好）由 infra KVStore 承接，settings 域只保留 profile/pref 强类型表。
 *
 * v2-full-takeover P0-1/P0-2：补 ai_providers 的 Flow 响应式读与列级 setter，
 * 语义逐条对齐 Room `AIProviderDao`（含 RC68 active 互斥不变量的事务化）。
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

    // ── ai_providers（对齐 Room AIProviderDao 全集）──

    /**
     * 保存（对齐 Room `@Insert(onConflict = REPLACE)`）：不存在则插、存在则整行替换。
     * isActive=true 时先清全部 active —— 复刻 RC68 P0-1 不变量（全库最多一行 active），
     * 且两步收进单事务，消除 Room 版的非原子窗口。
     */
    suspend fun saveProvider(
        id: String, name: String, type: String, encryptedApiKey: String, baseUrl: String,
        defaultModel: String, isActive: Boolean, models: String, isEnabled: Boolean,
        useFullUrl: Boolean, useResponseApi: Boolean,
    ) = withContext(Dispatchers.IO) {
        db.transaction {
            if (isActive) q.deactivateAllProviders()
            q.insertOrReplaceProvider(
                id, name, type, encryptedApiKey, baseUrl, defaultModel,
                if (isActive) 1L else 0L, models,
                if (isEnabled) 1L else 0L,
                if (useFullUrl) 1L else 0L,
                if (useResponseApi) 1L else 0L,
            )
        }
    }

    suspend fun getProvider(id: String): Ai_providers? =
        withContext(Dispatchers.IO) { q.selectProviderById(id).executeAsOneOrNull() }

    suspend fun listProviders(): List<Ai_providers> =
        withContext(Dispatchers.IO) { q.selectAllProviders().executeAsList() }

    /** 响应式：Provider 列表（对齐 Room `getAllProviders(): Flow<List<AIProviderEntity>>`）。 */
    fun observeProviders(): Flow<List<Ai_providers>> =
        q.selectAllProviders().asFlow().mapToList(Dispatchers.IO)

    /** 响应式：当前激活 Provider（对齐 Room `getActiveProvider(): Flow<AIProviderEntity?>`）。 */
    fun observeActiveProvider(): Flow<Ai_providers?> =
        q.selectActiveProvider().asFlow().mapToOneOrNull(Dispatchers.IO)

    suspend fun getActiveProvider(): Ai_providers? =
        withContext(Dispatchers.IO) { q.selectActiveProvider().executeAsOneOrNull() }

    /** 切换激活（RC68 P0-1 互斥不变量事务化：清全部 + 置指定，一步到位）。 */
    suspend fun setActiveProvider(id: String) = withContext(Dispatchers.IO) {
        db.transaction {
            q.deactivateAllProviders()
            q.activateProvider(id)
        }
    }

    suspend fun deactivateAllProviders() =
        withContext(Dispatchers.IO) { q.deactivateAllProviders() }

    suspend fun deleteProvider(id: String) =
        withContext(Dispatchers.IO) { q.deleteProvider(id) }

    suspend fun setDefaultModel(id: String, model: String) =
        withContext(Dispatchers.IO) { q.setDefaultModel(model, id) }

    suspend fun setModels(id: String, models: String) =
        withContext(Dispatchers.IO) { q.setModels(models, id) }

    suspend fun setProviderEnabled(id: String, isEnabled: Boolean) =
        withContext(Dispatchers.IO) { q.setProviderEnabled(if (isEnabled) 1L else 0L, id) }

    /** RC71：仅更新密文列（加密失败中止路径由调用方保证，不触碰其它列）。 */
    suspend fun updateEncryptedApiKey(id: String, newEncrypted: String) =
        withContext(Dispatchers.IO) { q.updateEncryptedApiKey(newEncrypted, id) }

    // ── P1-3 parity 校验原语（行数比对用）──

    suspend fun countProviders(): Long =
        withContext(Dispatchers.IO) { q.selectProviderCount().executeAsOne() }

    suspend fun countActiveProviders(): Long =
        withContext(Dispatchers.IO) { q.selectActiveProviderCount().executeAsOne() }
}
