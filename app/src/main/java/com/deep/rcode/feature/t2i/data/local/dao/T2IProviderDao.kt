package com.deep.rcode.feature.t2i.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.deep.rcode.feature.t2i.data.local.entity.T2IProviderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface T2IProviderDao {

    @Query("SELECT * FROM t2i_providers ORDER BY priority DESC, id ASC")
    fun getAllProviders(): Flow<List<T2IProviderEntity>>

    @Query("SELECT * FROM t2i_providers ORDER BY priority DESC, id ASC")
    suspend fun getAllProvidersOnce(): List<T2IProviderEntity>

    @Query("SELECT * FROM t2i_providers WHERE isActive = 1 LIMIT 1")
    fun getActiveProvider(): Flow<T2IProviderEntity?>

    @Query("SELECT * FROM t2i_providers WHERE isActive = 1 LIMIT 1")
    suspend fun getActiveProviderSync(): T2IProviderEntity?

    @Query("SELECT * FROM t2i_providers WHERE id = :id")
    suspend fun getProviderById(id: String): T2IProviderEntity?

    @Query("SELECT * FROM t2i_providers WHERE isEnabled = 1 ORDER BY priority DESC, id ASC")
    suspend fun getEnabledProvidersOnce(): List<T2IProviderEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProvider(provider: T2IProviderEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllProviders(providers: List<T2IProviderEntity>)

    @Update
    suspend fun updateProvider(provider: T2IProviderEntity)

    @Query("DELETE FROM t2i_providers WHERE id = :id")
    suspend fun deleteProvider(id: String)

    /** 仓储级 invariant：saveProvider/setActiveProvider 先清所有 active 再把目标行置 1。 */
    @Query("UPDATE t2i_providers SET isActive = 0")
    suspend fun deactivateAllProviders()

    @Query("UPDATE t2i_providers SET isActive = 1 WHERE id = :id")
    suspend fun activateProvider(id: String)

    @Query("UPDATE t2i_providers SET encryptedApiKey = :newEncrypted WHERE id = :id")
    suspend fun updateEncryptedApiKey(id: String, newEncrypted: String)

    @Query("UPDATE t2i_providers SET endpointMode = :mode WHERE id = :id")
    suspend fun updateEndpointMode(id: String, mode: String)

    @Query("UPDATE t2i_providers SET isEnabled = :isEnabled WHERE id = :id")
    suspend fun setProviderEnabled(id: String, isEnabled: Boolean)

    @Query("UPDATE t2i_providers SET priority = :priority WHERE id = :id")
    suspend fun setPriority(id: String, priority: Int)
}
