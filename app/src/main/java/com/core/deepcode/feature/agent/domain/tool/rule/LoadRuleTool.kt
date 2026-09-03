package com.core.deepcode.feature.agent.domain.tool.rule

import com.core.deepcode.core.util.FileLogger
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.rule.RuleRegistry
import com.core.deepcode.feature.agent.domain.tool.AgentTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/**
 * 按需加载一份分层规则的完整正文（D3-3，对齐 norm-chain-design.md §3.9.3）：
 *
 * 系统提示（step 前注入）只携带各分层规则的**摘要**（[com.core.deepcode.feature.agent.domain.rule.RuleAsset.summary]，
 * 少量 token）；AI 判断某条规则适用、需要完整正文时调用本工具，把该规则的 body 注入上下文，
 * 供 AI 严格按正文行事（对齐 3.2 SOP 的「摘要常驻 + loadSop 按需取正文」模式，摘要/正文两级形态独立自研）。
 *
 * 规则来源 [RuleRegistry]：四级（全局/项目/工作区/模块）按 priority 合并，名称经
 * `/rules` 命令或注入的摘要清单可见；本工具按名称精确查找，不存在返回明确错误。
 */
class LoadRuleTool @Inject constructor(
    private val ruleRegistry: RuleRegistry
) : AgentTool() {
    private companion object {
        const val TAG = "LoadRuleTool"
    }

    override val name = "load_rule"
    override val capabilities = setOf(ToolCapability.READ_AGENT_CONFIG)
    override val description =
        "加载指定分层规则的完整正文（全局/项目/工作区/模块四级规则，名称与系统提示「分层规则摘要」清单一致）。" +
            "当系统提示注入的规则摘要适用于当前任务、需要查看其完整条款时调用；正文仅供阅读参考，不执行任何动作。"

    override val parameters: Map<String, ToolParameter> = mapOf(
        "rule_name" to ToolParameter(
            name = "rule_name",
            type = ParameterType.STRING,
            description = "要加载的规则名称（与系统提示「分层规则摘要」清单中的名称一致）。",
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
        val ruleName = args["rule_name"]?.jsonPrimitive?.contentOrNull?.trim()
        if (ruleName.isNullOrEmpty()) {
            return ToolResult.Error("缺少必需参数: rule_name", "MISSING_RULE_NAME")
        }
        if (context.projectRoot.isBlank()) {
            return ToolResult.Error("当前无工作区，无法定位分层规则", "NO_WORKSPACE")
        }
        val asset = ruleRegistry.findByName(context.projectRoot, ruleName)
            ?: return ToolResult.Error("未找到规则「$ruleName」，可用 /rules 命令查看全部规则清单", "RULE_NOT_FOUND")
        FileLogger.d(TAG, "load_rule 加载成功: ${asset.name} (${asset.body.length} 字符)")
        return ToolResult.Success(
            JsonPrimitive(
                "【规则：${asset.name}（${asset.layer.name}）】\n${asset.body}"
            )
        )
    }
}
