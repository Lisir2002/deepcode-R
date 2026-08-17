package com.R.codecore.feature.agent.domain.skill

import java.io.File

/**
 * 技能数据源抽象接口（只读），支持多渠道加载（本地目录、内置资产等）。
 */
interface SkillSource {
    /** 扫描并返回该数据源下当前所有合法的 Skill。 */
    fun listSkills(): List<Skill>

    /** 读取指定 skill 的完整指令正文；不存在或解析失败时返回 null。 */
    fun loadInstructions(name: String): String?
}

/**
 * 可写技能数据源（RC74 新增），支持安装/卸载/更新。
 *
 * 与只读 [SkillSource] 分离：内置技能只实现 [SkillSource]，用户技能目录实现两者，
 * 语义清晰且内置来源天然不可变。
 */
interface MutableSkillSource {
    /** 技能根目录。 */
    val skillsRoot: File

    /**
     * 安装技能：把 [sourceDir]（含 SKILL.md 及附属文件）复制进技能目录，并做格式校验。
     * @return 安装后的技能；校验失败返回 null。
     */
    fun install(sourceDir: File): Skill?

    /**
     * 卸载技能。
     * @return 是否成功（技能不存在视为成功）。
     */
    fun uninstall(id: String): Boolean

    /**
     * 更新技能：用 [sourceDir] 覆盖 [id] 对应目录（先解压到临时目录校验，通过后原子替换）。
     * @return 更新后的技能；技能不存在或校验失败返回 null。
     */
    fun update(id: String, sourceDir: File): Skill?
}
