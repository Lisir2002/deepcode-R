package com.R.codecore.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 技能运行时状态表（RC74 新增）。
 *
 * 与技能文件分离存储：技能文件（SKILL.md）只描述技能内容，`enabled` 等运行时状态
 * 持久化在此表，避免「改文件即改状态」。
 *
 * @param id 技能唯一标识（= 技能目录名）。
 * @param enabled 是否启用（默认 true）。
 * @param version 安装/记录时的技能版本（semver），用于更新比对。
 * @param source 来源类型（BUILTIN/LOCAL），字符串常量。
 * @param installedAtMs 安装时间（毫秒）。
 */
@Entity(tableName = "skill_state")
data class SkillStateEntity(
    @PrimaryKey val id: String,
    val enabled: Boolean = true,
    val version: String = "0.0.0",
    val source: String = "LOCAL",
    val installedAtMs: Long = System.currentTimeMillis()
)
