package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.deep.rcode.feature.agent.data.local.entity.UserConfirmedSentinelEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface UserConfirmedSentinelDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserConfirmedSentinelEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<UserConfirmedSentinelEntity>)

    @Query("SELECT * FROM zth_user_confirmed_sentinels WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): UserConfirmedSentinelEntity?

    /** C.4.1 ConfirmationCard 挂起时查：当前 chainId 下是否已有用户决策。 */
    @Query("SELECT * FROM zth_user_confirmed_sentinels WHERE chainId = :chainId ORDER BY chainIndex ASC")
    suspend fun getByChain(chainId: String): List<UserConfirmedSentinelEntity>

    /** 流式：某会话内所有 sentinel（UI 时间线用）。 */
    @Query("SELECT * FROM zth_user_confirmed_sentinels WHERE sessionId = :sessionId ORDER BY createdAtMs DESC")
    fun observeBySession(sessionId: String): Flow<List<UserConfirmedSentinelEntity>>

    /** C.4.3 崩溃恢复：会话下所有未过期 sentinel。 */
    @Query("SELECT * FROM zth_user_confirmed_sentinels WHERE sessionId = :sessionId AND (expireAtMs = -1L OR expireAtMs > :nowMs) ORDER BY createdAtMs ASC")
    suspend fun getUnexpiredBySession(sessionId: String, nowMs: Long): List<UserConfirmedSentinelEntity>

    /** C.4.2 一键回滚：某会话所有 sentinel 打 rollbackFlag=true。 */
    @Query("UPDATE zth_user_confirmed_sentinels SET rollbackFlag = 1 WHERE sessionId = :sessionId")
    suspend fun markAllRollbackBySession(sessionId: String)

    /** 备份导出。 */
    @Query("SELECT * FROM zth_user_confirmed_sentinels ORDER BY createdAtMs ASC")
    suspend fun getAllOnce(): List<UserConfirmedSentinelEntity>

    @Query("DELETE FROM zth_user_confirmed_sentinels WHERE id = :id")
    suspend fun deleteById(id: String)
}
