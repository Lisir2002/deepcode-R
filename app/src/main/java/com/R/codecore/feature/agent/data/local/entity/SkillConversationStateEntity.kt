package com.R.codecore.feature.agent.data.local.entity

/**
 * 技能对话级状态表（v47 新增）。
 *
 * 承载「对话 ↔ 技能」的运行时关系，支持对话级双向控制：
 * - `enabled = true`：该技能在此对话生效——对 [com.R.codecore.feature.agent.domain.skill.SkillScope.CONVERSATION]
 *   技能即「添加进对话」；对 GLOBAL/AGENT 技能表示用户显式在本对话启用（记录存在即覆盖跟随声明）。
 * - `enabled = false`：该技能在此对话被临时禁用（per-conversation override），本对话内严格隐藏。
 * - 无记录：跟随声明（CONVERSATION 休眠，GLOBAL/AGENT 生效）。
 *
 * @param skillId 技能 id（= 技能目录名）。
 * @param sessionId 对话 id（对应 [ChatSessionEntity.id]）。
 * @param enabled 该对话内是否生效（true=添加/启用，false=本对话禁用）。
 */

data class SkillConversationStateEntity(
     val skillId: String,
     val sessionId: String,
    val enabled: Boolean = true
)
