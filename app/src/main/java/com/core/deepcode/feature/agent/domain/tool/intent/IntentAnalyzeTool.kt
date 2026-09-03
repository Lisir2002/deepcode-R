package com.core.deepcode.feature.agent.domain.tool.intent

import com.core.deepcode.feature.agent.domain.input.UserInputParser
import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.model.AgentMessage
import com.core.deepcode.feature.agent.domain.tool.AbstractContextualTool
import com.core.deepcode.feature.agent.domain.tool.ParameterType
import com.core.deepcode.feature.agent.domain.tool.ToolCapability
import com.core.deepcode.feature.agent.domain.tool.ToolParameter
import com.core.deepcode.feature.agent.domain.tool.ToolPermissionPolicy
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * 意图判定平台（D0 语义层，对齐 DSH 任务编排形态判定）：模型在拆解用户请求前调用，
 * 工具内部跑通「规则预分类」后输出结构化结果——意图形态 form（goal/plan/jobs/schedule/
 * playbook/none）+ 行为模式 behaviorMode（design/execute/research/chat）+ 置信度 + 参数建议，
 * 模型据此执行下一步。
 *
 * 判定准则与「模型兜底」见 prompts/ 资产（D0-8，五形态判定准则）；本工具只做规则预分类，
 * 最终形态由模型按准则裁定；低置信时模型应回退 [com.core.deepcode.feature.agent.domain.tool.question.AskUserQuestionTool] 向用户澄清。
 *
 * 调用门控与频控（§3.10 审计定稿）：仅 Parser 意图分类为 task/command 时建议模型调用本工具
 * （[isGateEligible]），query/chat/file 轻意图跳过；判定结果按「会话 + 输入文本」缓存至下一轮
 * 意图变化（同轮不重复调，控制成本）；已澄清的歧义标记后不重复提示（[markClarified]）。
 */
class IntentAnalyzeTool @Inject constructor(
    private val parser: UserInputParser
) : AbstractContextualTool() {

    /**
     * 判定结果缓存：key = `sessionId::text`，命中即复用，避免同一文本在会话内被反复判定。
     * 内存缓存（对齐 ToolSessionState 会话级内存态定位），进程重启后自然失效。
     */
    private val verdictCache = ConcurrentHashMap<String, JsonObject>()

    /** 已澄清歧义标记：`sessionId::text`，标记后不再重复建议澄清（D0-4 防重复）。 */
    private val clarifiedKeys = ConcurrentHashMap.newKeySet<String>()

    private companion object {
        const val TAG = "IntentAnalyzeTool"

        val SCHEDULE_HINTS = listOf("每天", "每周", "定时", "提醒", "周期", "每早", "每晚", "每周末")
        val JOBS_HINTS = listOf("后台", "编译", "构建", "测试通过", "长任务", "后台运行", "持续构建")
        val PLAYBOOK_HINTS = listOf("流程", "剧本", "多阶段", "分步", "按步骤", "分阶段", "按流程")
        val PLAN_HINTS = listOf("方案", "设计", "规划", "计划", "怎么做", "如何实现", "多步骤", "架构", "选型")
        val GOAL_HINTS = listOf("长期", "目标", "里程碑", "整个项目", "持续目标", "总目标")
        val RESEARCH_HINTS = listOf("调研", "搜索", "查询", "了解", "评估", "比较", "查看", "分析")
        val CHAT_HINTS = listOf("你好", "谢谢", "你是谁", "再见", "在吗", "辛苦了")

        val FORMS = listOf("schedule", "jobs", "playbook", "plan", "goal", "none")
    }

    override val name = "intent_analyze"
    override val description =
        "用户请求意图判定平台：在开始拆解/执行用户请求前调用，内部做规则预分类并输出" +
            "意图形态（goal 长期目标 / plan 方案计划 / jobs 后台任务 / schedule 定时提醒 / playbook 流程剧本 / none 普通对话）" +
            "与行为模式（design 只设计不写码 / execute 动手执行 / research 只读调研 / chat 闲聊），" +
            "及置信度与参数建议。判定准则见系统提示词「意图形态判定准则」；最终形态由你按准则裁定，" +
            "低置信时用 askUserQuestion 向用户澄清后再定。"

    override val permissionPolicy = ToolPermissionPolicy.AUTO_APPROVE
    override val capabilities = setOf(ToolCapability.MODIFY_SESSION_STATE)

    override val parameters: Map<String, ToolParameter> = mapOf(
        "text" to ToolParameter(
            name = "text",
            type = ParameterType.STRING,
            description = "待判定的用户请求文本（缺省取本轮最近一条用户消息）",
            required = false
        ),
        "reason" to ToolParameter(
            name = "reason",
            type = ParameterType.STRING,
            description = "可选：你为什么做这次判定（便于审计）",
            required = false
        )
    )

    /** 判定结果。 */
    data class IntentVerdict(
        val form: String,
        val behaviorMode: String,
        val confidence: String,
        val reason: String,
        val params: Map<String, String>
    )

    override suspend fun executeWithContext(
        args: Map<String, JsonElement>,
        context: AgentContext
    ): ToolResult {
        val text = args["text"]?.jsonPrimitive?.contentOrNull?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: lastUserMessage(context)
        val reason = args["reason"]?.jsonPrimitive?.contentOrNull?.trim() ?: ""
        if (text.isEmpty()) {
            return ToolResult.Error(
                "无法判定：未提供 text 且上下文无用户消息",
                "MISSING_TEXT"
            )
        }
        val sessionId = context.sessionId ?: "default"
        val cacheKey = "$sessionId::$text"
        val payload = verdictCache[cacheKey] ?: run {
            val verdict = classify(text, reason)
            val p = buildPayload(verdict, cacheKey)
            verdictCache[cacheKey] = p
            p
        }
        return ToolResult.Success(payload)
    }

    private fun buildPayload(verdict: IntentVerdict, cacheKey: String): JsonObject {
        // 已澄清的歧义不再重复建议澄清（D0-4 防重复）；仍输出判定结果供模型继续。
        val clarifyHint = if (clarifiedKeys.contains(cacheKey)) {
            "该歧义此前已澄清，按已确认理解继续，不再重复提问。"
        } else {
            "低置信（medium 以下或理解不确定）时用 askUserQuestion 给 2-3 个候选让用户确认，不要猜着做。"
        }
        return JsonObject(
            mapOf(
                "intent" to JsonObject(
                    mapOf(
                        "form" to JsonPrimitive(verdict.form),
                        "behavior_mode" to JsonPrimitive(verdict.behaviorMode),
                        "confidence" to JsonPrimitive(verdict.confidence),
                        "reason" to JsonPrimitive(verdict.reason)
                    )
                ),
                "params" to JsonObject(verdict.params.mapValues { JsonPrimitive(it.value) }),
                "hint" to JsonPrimitive(
                    "判定结果供你参考，最终形态由你按「意图形态判定准则」裁定；$clarifyHint"
                )
            )
        )
    }

    /** D0-4：标记某会话某输入已澄清，后续判定该文本不再重复建议澄清。 */
    fun markClarified(sessionId: String, text: String) {
        if (text.isBlank()) return
        clarifiedKeys.add("$sessionId::$text")
    }

    /** D0-4：该会话该输入是否已澄清。 */
    fun isClarified(sessionId: String, text: String): Boolean =
        text.isNotBlank() && clarifiedKeys.contains("$sessionId::$text")

    /**
     * D0-5 调用门控：仅 Parser 意图分类为 task/command 时建议模型调用 intent_analyze，
     * query/chat/file 轻意图跳过（省成本）。返回 true 表示符合调用建议。
     */
    fun isGateEligible(intent: UserInputParser.IntentLabel): Boolean =
        intent == UserInputParser.IntentLabel.TASK || intent == UserInputParser.IntentLabel.COMMAND

    /**
     * 规则预分类：按关键词优先级 schedule → jobs → playbook → plan → goal → none 判定形态；
     * plan 形态强制 design 行为模式（审计定稿：批准前只出方案不改码）。
     */
    fun classify(text: String, reason: String = ""): IntentVerdict {
        val t = text.trim()
        val form = when {
            SCHEDULE_HINTS.any { t.contains(it) } -> "schedule"
            JOBS_HINTS.any { t.contains(it) } -> "jobs"
            PLAYBOOK_HINTS.any { t.contains(it) } -> "playbook"
            PLAN_HINTS.any { t.contains(it) } -> "plan"
            GOAL_HINTS.any { t.contains(it) } -> "goal"
            else -> "none"
        }
        val behaviorMode = when {
            form == "plan" -> "design"
            RESEARCH_HINTS.any { t.contains(it) } -> "research"
            CHAT_HINTS.any { t.contains(it) } -> "chat"
            else -> "execute"
        }
        val matched = countHits(t)
        val confidence = when {
            matched >= 2 -> "high"
            matched == 1 -> "medium"
            else -> "low"
        }
        val params = paramsFor(form, t)
        val reasonText = buildString {
            if (reason.isNotBlank()) append("用户说明: $reason; ")
            append("命中关键词 $matched 个; ")
            append("形态=(${if (form == "none") "普通对话" else form}) 行为=($behaviorMode)")
        }
        return IntentVerdict(form, behaviorMode, confidence, reasonText, params)
    }

    private fun countHits(t: String): Int =
        (SCHEDULE_HINTS + JOBS_HINTS + PLAYBOOK_HINTS + PLAN_HINTS + GOAL_HINTS + RESEARCH_HINTS)
            .count { t.contains(it) }

    private fun paramsFor(form: String, text: String): Map<String, String> = when (form) {
        "schedule" -> mapOf("frequency" to (SCHEDULE_HINTS.firstOrNull { text.contains(it) } ?: "每天"))
        "plan" -> mapOf("mode" to "PLAN")
        "playbook" -> mapOf("scope" to "先看剧本清单，按名称精确匹配，未命中回退 plan/goal")
        "jobs" -> mapOf("entry" to "job_start")
        "goal" -> mapOf("entry" to "goal set")
        else -> emptyMap()
    }

    private fun lastUserMessage(context: AgentContext): String =
        context.history.asReversed().firstOrNull { it is AgentMessage.UserMessage }
            ?.let { (it as AgentMessage.UserMessage).content }?.trim() ?: ""
}
