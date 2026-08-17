package com.R.codecore.feature.settings.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.R.codecore.feature.settings.data.local.entity.AIProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AIProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY id")
    fun getAllProviders(): Flow<List<AIProviderEntity>>

    @Query("SELECT * FROM ai_providers ORDER BY id")
    suspend fun getAllProvidersOnce(): List<AIProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllProviders(providers: List<AIProviderEntity>)

    @Query("SELECT * FROM ai_providers WHERE isActive = 1 LIMIT 1")
    fun getActiveProvider(): Flow<AIProviderEntity?>

    @Query("SELECT * FROM ai_providers WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProviderSync(): AIProviderEntity?

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun getProviderById(id: String): AIProviderEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: AIProviderEntity)

    @Update
    suspend fun updateProvider(provider: AIProviderEntity)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    @Query("UPDATE ai_providers SET isActive = 0")
    suspend fun deactivateAllProviders()

    @Query("UPDATE ai_providers SET isActive = 1 WHERE id = :id")
    suspend fun activateProvider(id: String)

    /**
     * RC68 SCHEMA 38：selectedModel 冗余列已删除，该字段合并到 defaultModel。
     * 为减少调用方改动，保留原方法名 setSelectedModel，内部更新的就是 defaultModel 列；
     * 同时新增语义更清晰的 setDefaultModel 方法。
     */
    @Query("UPDATE ai_providers SET defaultModel = :model WHERE id = :id")
    suspend fun setDefaultModel(id: String, model: String)

    /** @deprecated 请改用 setDefaultModel。名字保留以减少 RC68 过渡期改动，底层已重定向 defaultModel 列。 */
    @Deprecated(
        "列 selectedModel 已合并进 defaultModel，直接用 setDefaultModel",
        ReplaceWith("setDefaultModel(id, model)")
    )
    @Query("UPDATE ai_providers SET defaultModel = :model WHERE id = :id")
    suspend fun setSelectedModel(id: String, model: String)

    @Query("UPDATE ai_providers SET models = :models WHERE id = :id")
    suspend fun setModels(id: String, models: String)

    @Query("UPDATE ai_providers SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setProviderEnabled(id: String, isEnabled: Boolean)

    @Query("UPDATE ai_providers SET encryptedApiKey = :newEncrypted WHERE id = :id")
    suspend fun updateEncryptedApiKey(id: String, newEncrypted: String)
}
