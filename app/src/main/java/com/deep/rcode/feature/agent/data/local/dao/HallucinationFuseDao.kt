package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.deep.rcode.feature.agent.data.local.entity.HallucinationFuseEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HallucinationFuseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: HallucinationFuseEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<HallucinationFuseEntity>)

    @Query("SELECT * FROM zth_hallucination_fuses WHERE scope = 'GLOBAL' AND scopeId = '__zth_global__' LIMIT 1")
    suspend fun getGlobal(): HallucinationFuseEntity?

    @Query("SELECT * FROM zth_hallucination_fuses WHERE scope = 'SESSION' AND scopeId = :sessionId LIMIT 1")
    suspend fun getBySession(sessionId: String): HallucinationFuseEntity?

    /** 流式：全局 + 某会话 fuse（横幅横幅自动刷新）。 */
    @Query("SELECT * FROM zth_hallucination_fuses WHERE (scope = 'GLOBAL' AND scopeId = '__zth_global__') OR (scope = 'SESSION' AND scopeId = :sessionId)")
    fun observeGlobalAndSession(sessionId: String): Flow<List<HallucinationFuseEntity>>

    /**
     * C.4.6 LINK-INV-0~6：原子迁移事务（乐观锁）。
     * @return 1 成功；0 版本冲突（ROLLBACK 触发 → 上层必须抛 ConflictException 重试）。
     */
    @Query(
        """
        UPDATE zth_hallucination_fuses 
        SET state = :newState, linkageVersion = linkageVersion + 1, updatedAtMs = :nowMs
        WHERE id = :id AND linkageVersion = :expectedVersion
        """
    )
    suspend fun casUpdateState(
        id: String,
        expectedVersion: Long,
        newState: String,
        nowMs: Long
    ): Int

    @Query("SELECT linkageVersion FROM zth_hallucination_fuses WHERE id = :id LIMIT 1")
    suspend fun getVersion(id: String): Long?

    @Query("UPDATE zth_hallucination_fuses SET killSwitch1Triggered = 1, state = 'OPEN', updatedAtMs = :nowMs WHERE id = :id")
    suspend fun triggerKillSwitch1(id: String, nowMs: Long)

    @Query("SELECT * FROM zth_hallucination_fuses")
    suspend fun getAllOnce(): List<HallucinationFuseEntity>
}
