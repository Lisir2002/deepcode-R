package com.deep.rcode.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.deep.rcode.feature.agent.data.local.entity.SkillStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 技能运行时状态 DAO（RC74 新增）。
 */
@Dao
interface SkillStateDao {

    @Query("SELECT * FROM skill_state")
    fun getAll(): Flow<List<SkillStateEntity>>

    @Query("SELECT * FROM skill_state")
    suspend fun getAllOnce(): List<SkillStateEntity>

    @Query("SELECT * FROM skill_state WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SkillStateEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkillStateEntity)

    @Query("UPDATE skill_state SET enabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: String, enabled: Boolean)

    @Query("DELETE FROM skill_state WHERE id = :id")
    suspend fun deleteById(id: String)
}
