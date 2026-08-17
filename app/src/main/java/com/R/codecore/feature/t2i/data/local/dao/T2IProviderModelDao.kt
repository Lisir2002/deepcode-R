package com.R.codecore.feature.t2i.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.R.codecore.feature.t2i.data.local.entity.T2IProviderModelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface T2IProviderModelDao {

    @Query("SELECT * FROM t2i_provider_models WHERE providerId = :providerId ORDER BY modelId ASC")
    fun getModelsForProvider(providerId: String): Flow<List<T2IProviderModelEntity>>

    @Query("SELECT * FROM t2i_provider_models WHERE providerId = :providerId ORDER BY modelId ASC")
    suspend fun getModelsForProviderOnce(providerId: String): List<T2IProviderModelEntity>

    @Query("SELECT * FROM t2i_provider_models WHERE providerId = :providerId AND modelId = :modelId LIMIT 1")
    suspend fun getModel(providerId: String, modelId: String): T2IProviderModelEntity?

    @Query("SELECT * FROM t2i_provider_models ORDER BY providerId, modelId")
    suspend fun getAllModelsOnce(): List<T2IProviderModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertModel(model: T2IProviderModelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllModels(models: List<T2IProviderModelEntity>)

    @Update
    suspend fun updateModel(model: T2IProviderModelEntity)

    @Query("DELETE FROM t2i_provider_models WHERE id = :id")
    suspend fun deleteModel(id: String)

    @Query("DELETE FROM t2i_provider_models WHERE providerId = :providerId")
    suspend fun deleteModelsForProvider(providerId: String)

    @Query("UPDATE t2i_provider_models SET costPerImageTokens = :cost WHERE providerId = :providerId AND modelId = :modelId")
    suspend fun updateCost(providerId: String, modelId: String, cost: Int)
}
