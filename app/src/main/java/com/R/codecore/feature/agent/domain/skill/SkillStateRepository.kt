package com.R.codecore.feature.agent.domain.skill

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.data.local.dao.SkillStateDao
import com.R.codecore.feature.agent.data.local.entity.SkillStateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 技能解析结果（RC74 新增）：技能 + 其依赖解析状态。
 */
data class SkillResolution(
    val skill: Skill,
    val dependencies: List<Skill> = emptyList(),   // 已解析的依赖（依赖序，先依赖后自身）
    val missingDependencies: List<String> = emptyList(), // 缺失的依赖 id
    val disabledDependencies: List<String> = emptyList() // 被禁用的依赖 id
) {
    val isResolvable: Boolean
        get() = missingDependencies.isEmpty() && disabledDependencies.isEmpty()
}

/**
 * 技能状态仓库（RC74 新增）。
 *
 * 职责：
 * 1. 聚合磁盘技能扫描（[LocalDirectorySkillSource]）与 Room 运行时状态（[SkillStateDao]），
 *    把 `enabled` 叠加到 [Skill] 上，对外暴露响应式 [skillsFlow]。
 * 2. 提供启用/禁用、安装/卸载/更新的统一入口（同时维护磁盘与 Room 状态）。
 * 3. 依赖解析：自动递归解析技能依赖，含环检测与缺失/禁用检测。
 */
@Singleton
class SkillStateRepository @Inject constructor(
    private val localDirectorySkillSource: LocalDirectorySkillSource,
    private val skillStateDao: SkillStateDao
) {
    private companion object {
        const val TAG = "SkillStateRepository"
    }

    /** 磁盘技能变更刷新触发器（UI 在安装/卸载/更新后自增以触发重扫）。 */
    private val refreshTrigger = MutableStateFlow(0)

    /** 响应式技能列表：磁盘扫描 + Room 启用状态合并。 */
    val skillsFlow: Flow<List<Skill>> =
        combine(refreshTrigger, skillStateDao.getAll()) { _, states ->
            mergeEnabled(localDirectorySkillSource.listSkills(), states)
        }

    /** 一次性技能列表（含启用状态）。 */
    suspend fun listSkills(): List<Skill> {
        val states = skillStateDao.getAllOnce()
        return mergeEnabled(localDirectorySkillSource.listSkills(), states)
    }

    /**
     * 同步一次性技能列表（含启用状态）。
     *
     * 供非协程上下文（如系统提示词构建 [com.R.codecore.feature.agent.domain.prompt.SystemPromptProvider]）
     * 读取技能清单；内部用 [runBlocking] 在 IO 线程读取 Room 状态，避免阻塞调用线程。
     */
    fun listSkillsSync(): List<Skill> {
        val states = runBlocking(Dispatchers.IO) { skillStateDao.getAllOnce() }
        return mergeEnabled(localDirectorySkillSource.listSkills(), states)
    }

    private fun mergeEnabled(skills: List<Skill>, states: List<SkillStateEntity>): List<Skill> {
        val stateById = states.associateBy { it.id }
        return skills.map { skill ->
            val state = stateById[skill.id]
            if (state == null) skill.copy(enabled = true) else skill.copy(enabled = state.enabled)
        }.sortedBy { it.name.lowercase() }
    }

    /** 启用/禁用技能（即时生效，写 Room）。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        runCatching { skillStateDao.setEnabled(id, enabled) }
            .onFailure { FileLogger.e(TAG, "更新技能启用状态失败: $id", it) }
        refreshTrigger.value++
    }

    /** 安装技能：复制到技能目录 + 写 Room 状态。 */
    suspend fun install(sourceDir: java.io.File): Skill? {
        val installed = localDirectorySkillSource.install(sourceDir) ?: return null
        skillStateDao.upsert(
            SkillStateEntity(
                id = installed.id,
                enabled = true,
                version = installed.version,
                source = SkillSourceType.LOCAL.name
            )
        )
        refreshTrigger.value++
        return installed
    }

    /** 卸载技能：删除目录 + 删除 Room 状态。 */
    suspend fun uninstall(id: String): Boolean {
        val ok = localDirectorySkillSource.uninstall(id)
        if (ok) {
            runCatching { skillStateDao.deleteById(id) }
                .onFailure { FileLogger.e(TAG, "删除技能状态失败: $id", it) }
            refreshTrigger.value++
        }
        return ok
    }

    /** 更新技能：覆盖目录 + 更新 Room 版本。 */
    suspend fun update(id: String, sourceDir: java.io.File): Skill? {
        val updated = localDirectorySkillSource.update(id, sourceDir) ?: return null
        val existing = skillStateDao.getById(id)
        skillStateDao.upsert(
            SkillStateEntity(
                id = updated.id,
                enabled = existing?.enabled ?: true,
                version = updated.version,
                source = existing?.source ?: SkillSourceType.LOCAL.name,
                installedAtMs = existing?.installedAtMs ?: System.currentTimeMillis()
            )
        )
        refreshTrigger.value++
        return updated
    }

    /**
     * 依赖解析：返回 [id] 技能及其依赖（依赖序，先依赖后自身）。
     *
     * 规则：
     * - 自动递归解析 `dependencies`。
     * - 环检测：A→B→A 视为环，环上的技能会被跳过并计入 [SkillResolution.missingDependencies]。
     * - 缺失依赖：依赖 id 在磁盘上不存在，计入 [SkillResolution.missingDependencies]。
     * - 禁用依赖：依赖存在但被禁用，计入 [SkillResolution.disabledDependencies]。
     */
    suspend fun resolveSkillWithDependencies(id: String): SkillResolution? {
        val all = listSkills()
        val byId = all.associateBy { it.id }
        val target = byId[id] ?: return null

        val ordered = mutableListOf<Skill>()
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val missing = mutableListOf<String>()
        val disabled = mutableListOf<String>()
        val cycleBroken = mutableListOf<String>()

        fun visit(skill: Skill) {
            if (skill.id in visited) return
            if (skill.id in visiting) {
                // 检测到环：打破，记录并停止沿此分支深入
                cycleBroken.add(skill.id)
                return
            }
            visiting.add(skill.id)
            for (depId in skill.dependencies) {
                val dep = byId[depId]
                if (dep == null) {
                    missing.add(depId)
                } else {
                    if (!dep.enabled) disabled.add(depId)
                    visit(dep)
                }
            }
            visiting.remove(skill.id)
            visited.add(skill.id)
            ordered.add(skill)
        }

        visit(target)

        // 环上的技能可能未被加入 ordered，补齐（仅自身，不再展开其依赖）
        for (c in cycleBroken) {
            byId[c]?.let { if (it.id !in visited) { visited.add(it.id); ordered.add(it) } }
        }

        return SkillResolution(
            skill = target,
            dependencies = ordered.filter { it.id != id },
            missingDependencies = missing.distinct(),
            disabledDependencies = disabled.distinct()
        )
    }
}
