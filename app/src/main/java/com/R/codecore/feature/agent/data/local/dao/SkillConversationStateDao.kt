package com.R.codecore.feature.agent.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.R.codecore.feature.agent.data.local.entity.SkillConversationStateEntity
import kotlinx.coroutines.flow.Flow

/**
 * 技能对话级状态 DAO（v47 新增）。
 *
 * 提供「某对话内启用的技能 id 集合 / 禁用的技能 id 集合」查询，供
 * [com.R.codecore.feature.agent.domain.skill.SkillStateRepository] 做作用域过滤。
 */
@Dao
interface SkillConversationStateDao {

    /** 某对话内全部技能关系（含添加与禁用）。 */
    @Query("SELECT * FROM skill_conversation_state WHERE session_id = :sessionId")
    suspend fun getBySession(sessionId: String): List<SkillConversationStateEntity>

    /** 某对话内已启用的技能 id 集合（enabled=1）。 */
    @Query("SELECT skill_id FROM skill_conversation_state WHERE session_id = :sessionId AND enabled = 1")
    suspend fun getEnabledSkillIds(sessionId: String): List<String>

    /** 某对话内被禁用的技能 id 集合（enabled=0）。 */
    @Query("SELECT skill_id FROM skill_conversation_state WHERE session_id = :sessionId AND enabled = 0")
    suspend fun getDisabledSkillIds(sessionId: String): List<String>

    @Query("SELECT * FROM skill_conversation_state WHERE skill_id = :skillId AND session_id = :sessionId LIMIT 1")
    suspend fun getBySkillAndSession(skillId: String, sessionId: String): SkillConversationStateEntity?

    /** 响应式：某对话内全部技能关系（供对话技能面板实时刷新）。 */
    @Query("SELECT * FROM skill_conversation_state WHERE session_id = :sessionId")
    fun observeBySession(sessionId: String): Flow<List<SkillConversationStateEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SkillConversationStateEntity)

    @Query("DELETE FROM skill_conversation_state WHERE skill_id = :skillId AND session_id = :sessionId")
    suspend fun delete(skillId: String, sessionId: String)

    /** 技能卸载时清理其全部对话绑定。 */
    @Query("DELETE FROM skill_conversation_state WHERE skill_id = :skillId")
    suspend fun deleteBySkill(skillId: String)
}
