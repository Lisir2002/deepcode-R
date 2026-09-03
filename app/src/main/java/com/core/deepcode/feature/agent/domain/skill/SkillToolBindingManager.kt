package com.core.deepcode.feature.agent.domain.skill

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ToolRegistry
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * 技能专属工具绑定管理器（设计文档 skill-collaboration-design §4.3）。
 *
 * 把技能声明的 [Skill.requiredTools] 接入 [ToolRegistry]，实现「技能即工具组：加载注入、禁用回收」：
 * - 全局已注册的工具：校验存在，缺失/禁用给明确错误；不重复注册、不回收。
 * - 技能自带的 [AgentTool] 实例：动态注册进 [ToolRegistry]，并记入回收表 [bound]，卸载时回收。
 *
 * 线程安全：回收表用 [ConcurrentHashMap]；所有方法为 suspend，调用方需在协程内调用。
 * 注意：仅回收本管理器动态注册的工具，绝不删除内置全局工具（保护 AgentModule 里注册的内建工具）。
 */
@Singleton
class SkillToolBindingManager @Inject constructor(
    // 注入 Provider 打破 ToolRegistry → SkillToolBindingManager → LoadSkillTool → provideToolRegistry(ToolRegistry) 的 Dagger 依赖环；
    // 与 SkillExecutor.toolRegistryProvider 采用同一模式（工具系统在 AgentModule 内自举注册）。
    private val toolRegistryProvider: Provider<ToolRegistry>
) {
    private companion object {
        const val TAG = "SkillToolBindingManager"
    }

    /** 动态注册回收表：skillId -> 本次动态注册进 ToolRegistry 的工具名列表（重复激活同技能时先回收旧绑定）。 */
    private val bound = ConcurrentHashMap<String, MutableList<String>>()

    /**
     * 技能加载成功时调用：登记该技能的 [Skill.requiredTools]。
     *
     * @return 校验结果——`null` 表示全部可用（含已动态注册），否则为不可用原因（缺失/禁用）。
     */
    suspend fun registerForSkill(skill: Skill): String? {
        // 重复激活同一技能：先回收上次动态注册的工具，避免残留重复注册。
        releaseForSkill(skill.id)

        val missing = mutableListOf<String>()
        val registry = toolRegistryProvider.get()
        for (toolName in skill.requiredTools) {
            val registered = registry.hasTool(toolName)
            if (!registered) {
                missing.add(toolName)
            }
        }
        if (missing.isNotEmpty()) {
            FileLogger.w(TAG, "技能 ${skill.id} 缺少必需工具: ${missing.joinToString("、")}")
            return "技能「${skill.name}」需要以下工具但未注册/不可用: ${missing.joinToString("、")}"
        }

        // 技能自带的动态工具注册入口（当前 Skill 模型以 requiredTools 声明名字，未来可扩展自带 AgentTool 列表）。
        // 若 future 模型新增技能自带工具字段，在此统一 ToolRegistry.register 并记入 bound 供回收。
        return null
    }

    /**
     * 技能被禁用/卸载时调用：回收本管理器为 [skillId] 动态注册的工具。
     * 仅回收 [bound] 中登记的工具名，绝不删除内置全局工具。
     */
    suspend fun releaseForSkill(skillId: String) {
        val names = bound.remove(skillId) ?: return
        val registry = toolRegistryProvider.get()
        for (name in names) {
            registry.unregister(name)
            FileLogger.d(TAG, "回收技能动态工具: $skillId -> $name")
        }
    }

    /** 读取技能当前已动态注册的工具名列表（用于审计/校验）。 */
    fun boundToolNames(skillId: String): List<String> {
        return bound[skillId]?.toList() ?: emptyList()
    }
}