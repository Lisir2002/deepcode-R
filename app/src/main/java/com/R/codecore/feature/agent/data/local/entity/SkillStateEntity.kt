package com.R.codecore.feature.agent.data.local.entity

/**
 * 技能运行时状态表（RC74 新增，v47 扩展作用域覆盖）。
 *
 * 与技能文件分离存储：技能文件（SKILL.md）只描述技能内容，`enabled` 等运行时状态
 * 持久化在此表，避免「改文件即改状态」。
 *
 * @param id 技能唯一标识（= 技能目录名）。
 * @param enabled 是否启用（默认 true）。
 * @param version 安装/记录时的技能版本（semver），用于更新比对。
 * @param source 来源类型（BUILTIN/LOCAL），字符串常量。
 * @param installedAtMs 安装时间（毫秒）。
 * @param scopeOverride 作用域用户覆盖（v47）：NULL=跟随 frontmatter 声明；非空字符串（GLOBAL/AGENT/CONVERSATION）= 用户覆盖。
 * @param agentTypeOverride 指定 Agent 覆盖（v47）：当 scope 覆盖为 AGENT 时绑定的 agentType；NULL=跟随声明。
 */

data class SkillStateEntity(
     val id: String,
    val enabled: Boolean = true,
    val version: String = "0.0.0",
    val source: String = "LOCAL",
    val installedAtMs: Long = System.currentTimeMillis(),
     val scopeOverride: String? = null,
     val agentTypeOverride: String? = null
)
