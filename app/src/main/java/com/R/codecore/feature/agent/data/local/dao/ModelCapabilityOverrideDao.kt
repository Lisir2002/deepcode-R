package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.ModelCapabilityOverrideEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelCapabilityOverrideDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ModelCapabilityOverrideEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ModelCapabilityOverrideEntity>)

    /** 单个模型的手动覆盖：没有就返回 null（继续走启发式 + 兼容端点策略）。 */
    @Query("SELECT * FROM model_capability_overrides WHERE providerType = :providerType AND modelId = :modelId LIMIT 1")
    suspend fun getByProviderAndModel(providerType: String, modelId: String): ModelCapabilityOverrideEntity?

    /** 流式读取（设置页 UI 观察，用户实时切换复选框即时刷新标签角标）。 */
    @Query("SELECT * FROM model_capability_overrides WHERE providerType = :providerType AND modelId = :modelId LIMIT 1")
    fun observeByProviderAndModel(providerType: String, modelId: String): Flow<ModelCapabilityOverrideEntity?>

    /** 列出全部（用于备份导出）。 */
    @Query("SELECT * FROM model_capability_overrides")
    suspend fun getAllOnce(): List<ModelCapabilityOverrideEntity>

    /** 删除某模型覆盖（用户点"恢复自动推荐"按钮时调用）。 */
    @Query("DELETE FROM model_capability_overrides WHERE providerType = :providerType AND modelId = :modelId")
    suspend fun deleteByProviderAndModel(providerType: String, modelId: String)

    /** 全表清（极少用，用于测试或重置）。 */
    @Query("DELETE FROM model_capability_overrides")
    suspend fun deleteAll()
}
