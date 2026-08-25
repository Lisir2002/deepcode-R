package com.R.codecore.feature.agent.domain.tool.sop

import com.R.codecore.core.util.FileLogger
import com.R.codecore.feature.agent.domain.model.AgentContext
import com.R.codecore.feature.agent.domain.sop.SopRegistry
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ParameterType
import com.R.codecore.feature.agent.domain.tool.ToolCapability
import com.R.codecore.feature.agent.domain.tool.ToolParameter
import com.R.codecore.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 按需加载一份 SOP 标准作业的完整正文（D4-4，对齐 norm-chain-design.md §3.2）：
 *
 * 系统提示（step 前注入）只携带各 SOP 的**摘要**（名称 + whenToUse 一句话）；AI 判断某流程适用、
 * 需要完整编号步骤（操作 + 判定 + 产出/出错处理）时调用本工具，把该 SOP 的 body 注入上下文，
 * 供 AI 严格按步骤行事（对齐「清单 + loadSkill」模式，摘要/正文两级形态）。
 *
 * **SOP / Skill 双判据边界**（审计定稿）：SOP = 仓库内固定操作流程（发版/迁移/提交等，绑项目语义）；
 * Skill = 通用可复用技能（用户可增删的技能中心）。主判据按适用范围，辅助判据按步骤化程度。
 * 本工具仅负责按需取正文，边界判定由提示词指引 + 模型判断（见 prompts 区分指引）。
 */
class LoadSopTool @Inject constructor(
    private val sopRegistry: SopRegistry
) : AgentTool() {
    private companion object {
        const val TAG = "LoadSopTool"
    }

    override val name = "loadSop"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "加载指定 SOP 标准作业的完整正文（仓库内固定操作流程：发版/迁移/提交等，名称与系统提示「SOP 清单」一致）。" +
            "当系统提示注入的 SOP 摘要适用于当前任务、需要按编号步骤严格执行时调用；正文仅供阅读参考，不执行任何动作。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "sop_name" to ToolParameter(
            name = "sop_name",
            type = ParameterType.STRING,
            description = "要加载的 SOP 名称（与系统提示「SOP 清单」中的名称一致，如 10-release）。",
            required = true
        )
    )

    override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        // 缺省路径：无 AgentContext 时以「无会话上下文」执行（兼容无上下文调用场景）。
        return executeWithContext(
            args,
            AgentContext(currentFile = null, selectedCode = null, projectRoot = "", language = null)
        )
    }

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val sopName = args["sop_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (sopName.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数: sop_name", "MISSING_SOP_NAME")
        }
        val asset = sopRegistry.findByName(sopName)
            ?: return ToolResult.Error("未找到 SOP「$sopName」，可用系统提示「SOP 清单」查看全部流程", "SOP_NOT_FOUND")
        FileLogger.d(TAG, "loadSop 加载成功: ${asset.name} (${asset.body.length} 字符)")
        return ToolResult.Success(
            JsonPrimitive(
                "【SOP：${asset.name}（$sopName）】\n${asset.body}"
            )
        )
    }
}
