package com.R.codecore.feature.agent.domain.skill

import com.R.codecore.feature.agent.domain.model.AgentMode
import java.io.File

/**
 * 技能的执行形态。
 *
 * - [PROMPT]：纯提示词技能，加载后把指令正文注入上下文，无执行、无安全风险。
 * - [SCRIPT]：脚本技能，在 PRoot 容器内沙箱执行入口脚本，执行前需 ZTH 审批。
 * - [MCP]：MCP 包装技能，把技能调用映射到某个已连接的 MCP 工具。
 */
enum class SkillType {
    PROMPT, SCRIPT, MCP
}

/**
 * 技能来源类型。
 *
 * - [BUILTIN]：随 App 预置的内置技能（只读，不可卸载）。
 * - [LOCAL]：用户安装/导入到私有目录的技能（可读写）。
 */
enum class SkillSourceType {
    BUILTIN, LOCAL
}

/**
 * 技能作用域：定义「谁能用、能否被用户关闭」，为多 Agent 演进提供支撑。
 *
 * - [GLOBAL]：全局。系统级强制激活，所有 agent 必有，用户不可关闭。
 * - [COMMON]：通用。所有 agent 默认全局可用，用户在设置里可自定义开关。
 * - [AGENT]：agent 级。仅绑定 [Skill.agentType] 对应 agent 可用。
 *
 * 与 [SkillType]（执行形态）、[Skill.modes]（执行模式）正交，只决定适用范围。
 */
enum class SkillScope {
    GLOBAL, COMMON, AGENT
}

/**
 * 解析后的单个 Skill 模型（RC74 元数据升级）。
 *
 * 相比旧版（仅 name/description/requiredTools/dir/instructions），新增了版本、作者、标签、
 * 适用模式、执行类型、依赖、来源、入口脚本、MCP 工具等字段，为技能中心 UI、生命周期管理
 * 与执行分层（PROMPT/SCRIPT/MCP）提供支撑。
 *
 * @param id 技能唯一标识。约定为技能所在目录名（稳定、可读、可匹配，支持更新覆盖）。
 * @param name 技能名称（供大模型调用的唯一标识）。
 * @param description 技能描述（何时使用该技能）。
 * @param version 语义化版本号（semver，如 "1.2.0"），用于更新比对。
 * @param author 作者（可选）。
 * @param tags 标签列表（可选）。
 * @param modes 适用模式集合；空表示所有模式均适用。
 * @param type 执行形态（PROMPT/SCRIPT/MCP），缺省按 PROMPT。
 * @param dependencies 依赖的其他技能 id 列表；加载时自动递归解析（需环检测）。
 * @param enabled 是否启用（运行时状态，由 Room skill_state 表持久化，不写回技能文件）。
 * @param source 来源类型（BUILTIN/LOCAL）。
 * @param requiredTools 该技能所需的专属工具列表（可选）。
 * @param requiresRuntime 运行时依赖：容器内必须存在的可执行命令，如 [\"node\", \"python\"]，加载时预检查。
 * @param dir 技能所在的本地目录。
 * @param entry SCRIPT 类型：入口脚本相对路径（相对技能目录）。
 * @param mcpTool MCP 类型：绑定的 MCP 工具名（命名空间化，如 mcp__server__tool）。
 * @param icon 图标标识（可选）。
 * @param scope 技能作用域（GLOBAL/COMMON/AGENT），见 [SkillScope]。缺省按 COMMON（默认所有 agent 可用、可开关）。
 * @param agentType 当 [scope] 为 [SkillScope.AGENT] 时绑定的 agent 类型标识（如 "coding"），缺省为 null。
 * @param instructions 技能指令正文（剥离 Frontmatter 后的内容）。
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val version: String = "0.0.0",
    val author: String? = null,
    val tags: List<String> = emptyList(),
    val modes: Set<AgentMode> = emptySet(),
    val type: SkillType = SkillType.PROMPT,
    val dependencies: List<String> = emptyList(),
    val enabled: Boolean = true,
    val source: SkillSourceType = SkillSourceType.LOCAL,
    val requiredTools: List<String> = emptyList(),
    val requiresRuntime: List<String> = emptyList(),
    val dir: File? = null,
    val entry: String? = null,
    val mcpTool: String? = null,
    val icon: String? = null,
    val scope: SkillScope = SkillScope.COMMON,
    val agentType: String? = null,
    /**
     * 是否参与工作流自动触发：为 true 时，工作流会在新任务到来时智能判断是否自动加载/执行本技能，
     * 作为自动化流程的一环，无需关键词触发或依赖模型自觉调用 [com.R.codecore.feature.agent.domain.tool.skill.LoadSkillTool]。
     */
    val autoTrigger: Boolean = false,
    /**
     * 自动触发条件（自然语言）：供工作流触发决策器判断「什么场景该自动触发本技能」，
     * 如"用户给出新的编程任务，即将开始写/改代码之前"。缺省回退用 [description] 判断。
     */
    val triggerConditions: String? = null,
    /**
     * 自动触发关键词（高置信规则）：用户任务命中任意一个关键词即视为应自动触发本技能，
     * 走规则快筛直接入选，不依赖 LLM 决策器判断，保证明确的场景稳定触发。
     * 用于覆盖最典型、最无歧义的触发场景；模糊场景仍由 LLM 决策器兜底。
     */
    val triggerKeywords: List<String> = emptyList(),
    val instructions: String
)
