package com.core.deepcode.feature.agent.domain.skill

import com.core.deepcode.feature.agent.domain.model.AgentMode
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
 * 技能作用域 v2：定义「技能在哪些上下文生效」，为多 Agent 演进与对话级控制提供支撑。
 *
 * - [GLOBAL]：全局。所有 Agent、所有对话默认生效；用户可在「设置级」开关（[Skill.enabled]），
 *   也可在某个对话内临时禁用（per-conversation override）。
 * - [AGENT]：指定 Agent 级。仅绑定 [Skill.agentType] 对应 agent 生效（当前单 Agent 场景为 "coding"）。
 * - [CONVERSATION]：对话级。默认休眠（不进系统提示词、不可调用、不自动触发），
 *   仅当用户显式「添加」到某个对话后，才在该对话内全面生效（可见/可调用/可自动触发）。
 *
 * 与 [SkillType]（执行形态）、[Skill.modes]（执行模式）正交，只决定适用范围。
 * 兼容说明：旧枚举的 COMMON 语义（默认全 agent 可用、用户可开关）并入 [GLOBAL]；
 * 旧 frontmatter `scope: common` 由 [com.core.deepcode.feature.agent.domain.skill.SkillParser] 映射为 GLOBAL。
 */
enum class SkillScope {
    GLOBAL, AGENT, CONVERSATION
}

/**
 * 运行时依赖探针声明（S-3 运行时预检）——求值树中的「叶子」。
 *
 * 结构化声明一条「容器内必须满足的运行时条件」，由 [SkillRuntimeProbe] 在技能执行前受控探测。
 * 相比旧版裸字符串（仅 `command -v` 查 PATH 命令），新增：
 * - 按类型探测：命令 / Python 模块 / npm 全局包 / deb 包 / 文件存在性；
 * - 版本区间约束（下界 [minVersion] 语义 `>=` + 上界 [maxVersion] 语义 `<=`，语义化版本比较，
 *   仅 [check]=cmd 与 npmpkg 支持）；
 * - [installHint]：该探针失败时附带的安装建议（如 `apk add nodejs`），让模型/用户可照做；
 * - 所有 name/module/path 一律字符白名单校验，探测命令参数化执行，杜绝 shell 注入。
 *
 * @param check 探针类型：cmd（PATH 命令）/ mod（Python 模块）/ npmpkg（npm 全局包）/ dpkg（deb 包）/ file（文件存在）。
 * @param name 探测目标：命令名 / Python 模块名 / npm 包名 / deb 包名 / 文件路径。
 * @param minVersion 可选最低版本约束（语义 `>=`，如 "18" / "3.9"），仅 cmd 与 npmpkg 支持。
 * @param maxVersion 可选最高版本约束（语义 `<=`，如 "22"），仅 cmd 与 npmpkg 支持。
 * @param installHint 可选安装建议，探针失败时拼接到失败原因返回。
 */
data class RuntimeProbe(
    val check: String,
    val name: String,
    val minVersion: String? = null,
    val maxVersion: String? = null,
    val installHint: String? = null
) {
    companion object {
        const val CHECK_CMD = "cmd"
        const val CHECK_MOD = "mod"
        const val CHECK_NPM = "npmpkg"
        const val CHECK_DPKG = "dpkg"
        const val CHECK_FILE = "file"
    }
}

/**
 * S-3 运行时预检求值树（完整布尔 DSL）。
 *
 * `requires_runtime` 从「探针列表 = 全 AND」升级为可组合的布尔表达式：
 * - [Leaf]：单条 [RuntimeProbe]（叶子）；
 * - [And]：所有子项必须全部满足（`&&`）；
 * - [Or]：任一子项满足即通过（`||`）；
 * - [Not]：子项不满足才通过（`!`）。
 *
 * 由 [SkillProbeExprParser] 从 `expr` 字符串（如 `cmd:node>=18<=22 && (mod:numpy || cmd:python3)`）
 * 解析生成；旧格式（YAML 对象列表 / 字符串列表 / 逗号串）也统一归一为 [And]。
 * 求值由 [SkillRuntimeProbe] 完成：只做结构化逻辑组合，绝不 eval，叶子仍走白名单参数化探测。
 */
sealed class RuntimeProbeExpr {
    data class Leaf(val probe: RuntimeProbe) : RuntimeProbeExpr()
    data class And(val children: List<RuntimeProbeExpr>) : RuntimeProbeExpr()
    data class Or(val children: List<RuntimeProbeExpr>) : RuntimeProbeExpr()
    data class Not(val child: RuntimeProbeExpr) : RuntimeProbeExpr()
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
 * @param requiresRuntime 运行时预检求值树（S-3 运行时预检），见 [RuntimeProbeExpr]；null = 无预检。兼容旧版裸命令字符串（按 cmd 处理）。
 * @param dir 技能所在的本地目录。
 * @param entry SCRIPT 类型：入口脚本相对路径（相对技能目录）。
 * @param mcpTool MCP 类型：绑定的 MCP 工具名（命名空间化，如 mcp__server__tool）。
 * @param icon 图标标识（可选）。
 * @param scope 技能作用域（GLOBAL/AGENT/CONVERSATION），见 [SkillScope]。缺省按 GLOBAL（默认所有 agent 可用、可开关）。
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
    val requiresRuntime: RuntimeProbeExpr? = null,
    val dir: File? = null,
    val entry: String? = null,
    val mcpTool: String? = null,
    val icon: String? = null,
    val scope: SkillScope = SkillScope.GLOBAL,
    val agentType: String? = null,
    /**
     * 是否参与工作流自动触发：为 true 时，工作流会在新任务到来时智能判断是否自动加载/执行本技能，
     * 作为自动化流程的一环，无需关键词触发或依赖模型自觉调用 [com.core.deepcode.feature.agent.domain.tool.skill.LoadSkillTool]。
     */
    val autoTrigger: Boolean = false,
    /**
     * 自动触发条件（自然语言）：供工作流触发决策器判断「什么场景该自动触发本技能」，
     * 如"用户给出新的编程任务，即将开始写/改代码之前"。缺省回退用 [description] 判断。
     */
    val triggerConditions: String? = null,
    /**
     * 自动触发信号词（辅助信号，非决策规则）：供工作流触发决策器参考的典型高频表达。
     *
     * **触发决策铁律：模型决策 > 关键词**。关键词绝不直接参与触发判定、永不高于模型判断：
     * - 主路径：由 LLM 触发决策器主导判断任务意图（模型是唯一决策者，能理解口语化/模糊表达）；
     *   关键词作为「典型触发信号词」喂给模型，辅助模型聚焦，但不作为硬性规则。
     * - 兜底路径：仅当模型链路完全不可用（异常）时，才回退用关键词匹配保底，避免明确任务极端落空。
     *
     * 后续所有声明 `auto_trigger` 的技能都必须遵循该原则设计。
     */
    val triggerKeywords: List<String> = emptyList(),
    val instructions: String
)
