package com.core.deepcode.feature.agent.domain.skill

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.datalayer.repository.AgentRepository as V2AgentRepository
import com.core.deepcode.feature.agent.data.local.entity.SkillConversationStateEntity
import com.core.deepcode.feature.agent.data.local.entity.SkillStateEntity
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
 * 技能状态仓库（RC74 新增，v47 扩展作用域覆盖 + 对话级双向控制）。
 *
 * 职责：
 * 1. 聚合磁盘技能扫描（[LocalDirectorySkillSource]）与 Room 运行时状态（[SkillStateDao]），
 *    把 `enabled` 与作用域用户覆盖（scope_override/agent_type_override）叠加到 [Skill] 上，
 *    对外暴露响应式 [skillsFlow]。
 * 2. 提供启用/禁用、安装/卸载/更新的统一入口（同时维护磁盘与 Room 状态）。
 * 3. 依赖解析：自动递归解析技能依赖，含环检测与缺失/禁用检测。
 * 4. 作用域过滤（[filterVisibleSkills]）：按「当前 agent + 当前会话」严格隐藏不匹配的技能，
 *    供 SystemPromptProvider / 自动触发候选 / LoadSkillTool 联动使用。
 * 5. 对话级双向控制：添加对话级技能（enabled=true）与对话内临时禁用（enabled=false）。
 */
@Singleton
class SkillStateRepository @Inject constructor(
    private val localDirectorySkillSource: LocalDirectorySkillSource,
    private val v2Agent: V2AgentRepository,
) {
    private companion object {
        const val TAG = "SkillStateRepository"
        const val DEFAULT_AGENT_TYPE = "coding" // 当前单 Agent 场景；多 Agent 演进后由调用方传入动态值
    }

    

    private suspend fun getSkillStates(): List<SkillStateEntity> =
        v2Agent.listSkillStates().map { it.toEntity() }

    private fun getSkillStatesSync(): List<SkillStateEntity> =
        runBlocking(Dispatchers.IO) { v2Agent.listSkillStates().map { it.toEntity() } }

    private suspend fun getConvStates(sessionId: String): List<SkillConversationStateEntity> =
        v2Agent.listSkillConversationStates(sessionId).map { it.toEntity() }

    private fun getConvStatesSync(sessionId: String): List<SkillConversationStateEntity> =
        runBlocking(Dispatchers.IO) { v2Agent.listSkillConversationStates(sessionId).map { it.toEntity() } }

    /** 磁盘技能变更刷新触发器（UI 在安装/卸载/更新后自增以触发重扫）。 */
    private val refreshTrigger = MutableStateFlow(0)

    /** 响应式技能列表：磁盘扫描 + Room 启用状态与作用域覆盖合并。 */
    val skillsFlow: Flow<List<Skill>> =
        combine(
            refreshTrigger,
            v2Agent.observeAllSkillStates().map { list -> list.map { it.toEntity() } }
        ) { _, states ->
            mergeWithState(localDirectorySkillSource.listSkills(), states)
        }

    /** 一次性技能列表（含启用状态与作用域覆盖）。 */
    suspend fun listSkills(): List<Skill> {
        val states = getSkillStates()
        return mergeWithState(localDirectorySkillSource.listSkills(), states)
    }

    /** 技能根目录（导入/导出/编辑器定位目录用）。 */
    fun skillsRoot(): java.io.File = localDirectorySkillSource.skillsRoot

    /**
     * 同步一次性技能列表（含启用状态与作用域覆盖）。
     *
     * 供非协程上下文（如系统提示词构建 [com.core.deepcode.feature.agent.domain.prompt.SystemPromptProvider]）
     * 读取技能清单；内部用 [runBlocking] 在 IO 线程读取 Room 状态，避免阻塞调用线程。
     */
    fun listSkillsSync(): List<Skill> {
        val states = getSkillStatesSync()
        return mergeWithState(localDirectorySkillSource.listSkills(), states)
    }

    private fun mergeWithState(skills: List<Skill>, states: List<SkillStateEntity>): List<Skill> {
        val stateById = states.associateBy { it.id }
        return skills.map { skill ->
            val state = stateById[skill.id]
            if (state == null) {
                skill.copy(enabled = true)
            } else {
                skill.copy(
                    enabled = state.enabled,
                    // 作用域用户覆盖：非空且可解析时覆盖 frontmatter 声明，否则跟随声明。
                    scope = state.scopeOverride?.let { ov -> runCatching { SkillScope.valueOf(ov) }.getOrNull() }
                        ?: skill.scope,
                    agentType = state.agentTypeOverride ?: skill.agentType
                )
            }
        }.sortedBy { it.name.lowercase() }
    }

    /** 启用/禁用技能（即时生效，写 Room）。 */
    suspend fun setEnabled(id: String, enabled: Boolean) {
        runCatching {
            v2Agent.setSkillStateEnabled(id, if (enabled) 1L else 0L)
        }
            .onFailure { FileLogger.e(TAG, "更新技能启用状态失败: $id", it) }
        refreshTrigger.value++
    }

    /** 安装技能：复制到技能目录 + 写 Room 状态。 */
    suspend fun install(sourceDir: java.io.File): Skill? {
        val installed = localDirectorySkillSource.install(sourceDir) ?: return null
        v2Agent.upsertSkillState(
            id = installed.id, enabled = 1L, version = installed.version,
            source = SkillSourceType.LOCAL.name,
            installedAtMs = System.currentTimeMillis(), scopeOverride = null, agentTypeOverride = null,
        )
        refreshTrigger.value++
        return installed
    }

    /** 卸载技能：删除目录 + 删除 Room 状态（含对话级绑定）。 */
    suspend fun uninstall(id: String): Boolean {
        val ok = localDirectorySkillSource.uninstall(id)
        if (ok) {
            runCatching {
                v2Agent.deleteSkillStateById(id)
                v2Agent.deleteSkillConversationStatesBySkill(id)
            }
                .onFailure { FileLogger.e(TAG, "删除技能状态失败: $id", it) }
            refreshTrigger.value++
        }
        return ok
    }

    /** 更新技能：覆盖目录 + 更新 Room 版本。 */
    suspend fun update(id: String, sourceDir: java.io.File): Skill? {
        val updated = localDirectorySkillSource.update(id, sourceDir) ?: return null
        val existing = v2Agent.getSkillState(id)?.toEntity()
        v2Agent.upsertSkillState(
            id = updated.id,
            enabled = if (existing?.enabled ?: true) 1L else 0L,
            version = updated.version,
            source = existing?.source ?: SkillSourceType.LOCAL.name,
            installedAtMs = existing?.installedAtMs ?: System.currentTimeMillis(),
            scopeOverride = existing?.scopeOverride,
            agentTypeOverride = existing?.agentTypeOverride,
        )
        refreshTrigger.value++
        return updated
    }

    /** 设置作用域用户覆盖（NULL=清除覆盖，跟随 frontmatter 声明）。AGENT 级可同时设置绑定的 agentType。 */
    suspend fun setScopeOverride(id: String, scope: SkillScope?, agentType: String? = null) {
        runCatching {
            v2Agent.setSkillStateScopeOverride(id, scope?.name, if (scope == SkillScope.AGENT) agentType else null)
        }.onFailure { FileLogger.e(TAG, "更新技能作用域覆盖失败: $id", it) }
        refreshTrigger.value++
    }

    /** 对话级双向控制：设置技能在某对话内的生效状态（true=添加/启用，false=本对话临时禁用）。 */
    suspend fun setConversationEnabled(skillId: String, sessionId: String, enabled: Boolean) {
        runCatching {
            v2Agent.upsertSkillConversationState(skillId, sessionId, if (enabled) 1L else 0L)
        }.onFailure { FileLogger.e(TAG, "更新技能对话状态失败: $skillId / $sessionId", it) }
        refreshTrigger.value++
    }

    /** 移除技能在某对话的绑定记录（恢复跟随声明）。 */
    suspend fun removeConversationBinding(skillId: String, sessionId: String) {
        runCatching {
            v2Agent.deleteSkillConversationState(skillId, sessionId)
        }
            .onFailure { FileLogger.e(TAG, "移除技能对话绑定失败: $skillId / $sessionId", it) }
        refreshTrigger.value++
    }

    /** 某对话内全部技能关系（供对话技能面板展示）。 */
    suspend fun listConversationStates(sessionId: String): List<SkillConversationStateEntity> =
        getConvStates(sessionId)

    /** 某对话内某技能的绑定状态（无绑定返回 null = 跟随声明）。 */
    suspend fun getConversationState(skillId: String, sessionId: String): SkillConversationStateEntity? =
        v2Agent.getSkillConversationState(skillId, sessionId)?.toEntity()

    /**
     * 作用域严格隐藏过滤：返回在「当前 agent + 当前会话」下可见的技能。
     *
     * - 全局 [Skill.enabled] 为 false → 排除。
     * - [SkillScope.AGENT]：仅当 [Skill.agentType] 与当前 agentType 匹配（无 agentType 上下文时不可见）。
     * - [SkillScope.CONVERSATION]：需该会话存在 enabled=true 绑定（否则休眠）。
     * - [SkillScope.GLOBAL]/[SkillScope.AGENT]：若该会话存在 enabled=false 绑定（对话内临时禁用）→ 排除。
     *
     * @param sessionId 当前会话 id；为 null 时（无对话上下文）退化为仅按 enabled 过滤。
     * @param agentType 当前 Agent 类型（当前单 Agent 场景默认 "coding"，多 Agent 演进后由调用方传入动态值）。
     */
    suspend fun filterVisibleSkills(
        skills: List<Skill>,
        sessionId: String?,
        agentType: String = DEFAULT_AGENT_TYPE
    ): List<Skill> {
        if (sessionId == null) return skills.filter { it.enabled }
        val convStates = getConvStates(sessionId).associateBy { it.skillId }
        return filterByConvStates(skills, convStates, agentType)
    }

    /**
     * 同步版作用域过滤（供非协程上下文，如 SystemPromptProvider 提示词构建；
     * 内部用 [runBlocking] 在 IO 线程读取 Room 状态，与 [listSkillsSync] 同模式）。
     */
    fun filterVisibleSkillsSync(
        skills: List<Skill>,
        sessionId: String?,
        agentType: String = DEFAULT_AGENT_TYPE
    ): List<Skill> {
        if (sessionId == null) return skills.filter { it.enabled }
        val convStates = getConvStatesSync(sessionId).associateBy { it.skillId }
        return filterByConvStates(skills, convStates, agentType)
    }

    private fun filterByConvStates(
        skills: List<Skill>,
        convStates: Map<String, SkillConversationStateEntity>,
        agentType: String
    ): List<Skill> = skills.filter { skill ->
        if (!skill.enabled) return@filter false
        when (skill.scope) {
            SkillScope.AGENT -> skill.agentType == agentType
            SkillScope.CONVERSATION -> convStates[skill.id]?.enabled == true
            SkillScope.GLOBAL -> convStates[skill.id]?.enabled != false
        }
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

    // ── V2 映射 ──────────────────────────────────────────────────────

    private fun com.core.deepcode.datalayer.sqldelight.agent.Skill_state.toEntity() = SkillStateEntity(
        id = id,
        enabled = enabled == 1L,
        version = version,
        source = source,
        installedAtMs = installed_at_ms,
        scopeOverride = scope_override,
        agentTypeOverride = agent_type_override,
    )

    private fun com.core.deepcode.datalayer.sqldelight.agent.Skill_conversation_state.toEntity() = SkillConversationStateEntity(
        skillId = skill_id,
        sessionId = session_id,
        enabled = enabled == 1L,
    )
}