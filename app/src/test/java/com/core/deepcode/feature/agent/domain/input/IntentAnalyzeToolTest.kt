package com.core.deepcode.feature.agent.domain.input

import com.core.deepcode.feature.agent.domain.model.AgentContext
import com.core.deepcode.feature.agent.domain.model.AgentMessage
import com.core.deepcode.feature.agent.domain.tool.ToolResult
import com.core.deepcode.feature.agent.domain.tool.intent.IntentAnalyzeTool
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D0-5 语义层：IntentAnalyzeTool 判定平台单测。
 * 对齐 norm-chain §3.10 验收：预分类命中五形态 + behaviorMode、plan 强制 design、
 * Parser 门控（task/command 才调）、结果缓存、已澄清防重复标记。
 */
class IntentAnalyzeToolTest {

    private val tool = IntentAnalyzeTool(UserInputParser())

    private fun ctx(sessionId: String = "s1", history: List<AgentMessage> = emptyList()) =
        AgentContext(
            currentFile = null,
            selectedCode = null,
            projectRoot = "",
            language = null,
            history = history,
            sessionId = sessionId
        )

    private fun args(text: String): Map<String, JsonElement> =
        mapOf("text" to JsonPrimitive(text))

    // —— 五形态预分类 ——

    @Test
    fun scheduleHints_classifySchedule() {
        assertEquals("schedule", tool.classify("每天提醒我跑测试").form)
    }

    @Test
    fun jobsHints_classifyJobs() {
        assertEquals("jobs", tool.classify("后台编译这个项目").form)
    }

    @Test
    fun playbookHints_classifyPlaybook() {
        assertEquals("playbook", tool.classify("按流程分步发布版本").form)
    }

    @Test
    fun planHints_classifyPlan() {
        assertEquals("plan", tool.classify("设计一个方案怎么实现登录").form)
    }

    @Test
    fun goalHints_classifyGoal() {
        assertEquals("goal", tool.classify("这是整个项目的长期目标").form)
    }

    @Test
    fun noHints_classifyNone() {
        assertEquals("none", tool.classify("今天天气怎么样").form)
    }

    // —— behaviorMode ——

    @Test
    fun planForm_forcesDesign() {
        assertEquals("design", tool.classify("如何实现多步骤架构").behaviorMode)
    }

    @Test
    fun researchHints_classifyResearch() {
        assertEquals("research", tool.classify("调研一下这个库").behaviorMode)
    }

    @Test
    fun chatHints_classifyChat() {
        assertEquals("chat", tool.classify("你好，你是谁").behaviorMode)
    }

    @Test
    fun defaultClassifyExecute() {
        assertEquals("execute", tool.classify("修复登录崩溃").behaviorMode)
    }

    // —— 置信度与参数 ——

    @Test
    fun confidence_levels() {
        assertEquals("low", tool.classify("随便说说").confidence)
        assertEquals("medium", tool.classify("每天跑测试").confidence)
        assertEquals("high", tool.classify("每天定时提醒我后台编译").confidence)
    }

    @Test
    fun scheduleParams_carriesFrequency() {
        assertEquals("每天", tool.classify("每天提醒我").params["frequency"])
    }

    @Test
    fun planParams_carriesPlanMode() {
        assertEquals("PLAN", tool.classify("设计一个方案").params["mode"])
    }

    // —— 调用门控 ——

    @Test
    fun gateEligible_onlyTaskOrCommand() {
        assertTrue(tool.isGateEligible(UserInputParser.IntentLabel.TASK))
        assertTrue(tool.isGateEligible(UserInputParser.IntentLabel.COMMAND))
        assertFalse(tool.isGateEligible(UserInputParser.IntentLabel.QUERY))
        assertFalse(tool.isGateEligible(UserInputParser.IntentLabel.FILE))
        assertFalse(tool.isGateEligible(UserInputParser.IntentLabel.UNKNOWN))
    }

    // —— 执行与缓存 ——

    @Test
    fun execute_withText_returnsStructuredResult() = runBlocking {
        val result = tool.executeWithContext(args("每天提醒我跑测试"), ctx()) as ToolResult.Success
        val obj = result.data.jsonObject
        val intent = obj["intent"]!!.jsonObject
        assertEquals("schedule", intent["form"]!!.jsonPrimitive.content)
        assertEquals("execute", intent["behavior_mode"]!!.jsonPrimitive.content)
        assertEquals("每天", obj["params"]!!.jsonObject["frequency"]!!.jsonPrimitive.content)
    }

    @Test
    fun execute_sameTextSameSession_hitsCache() = runBlocking {
        val c = ctx("s1")
        val first = tool.executeWithContext(args("后台编译这个项目"), c) as ToolResult.Success
        val second = tool.executeWithContext(args("后台编译这个项目"), c) as ToolResult.Success
        // 缓存复用：两次输出一致（同一 JsonObject 引用即命中缓存）
        assertTrue(first.data === second.data)
    }

    @Test
    fun execute_missingText_fallsBackToLastUserMessage() = runBlocking {
        val c = ctx(history = listOf(AgentMessage.UserMessage(content = "每天提醒我跑测试")))
        val result = tool.executeWithContext(emptyMap(), c) as ToolResult.Success
        val form = result.data.jsonObject["intent"]!!.jsonObject["form"]!!.jsonPrimitive.content
        assertEquals("schedule", form)
    }

    @Test
    fun execute_noTextNoHistory_returnsError() = runBlocking {
        val result = tool.executeWithContext(emptyMap(), ctx())
        assertTrue(result is ToolResult.Error)
    }

    // —— 已澄清防重复 ——

    @Test
    fun clarified_flagSuppressesClarifyHint() = runBlocking {
        tool.markClarified("s1", "设计一个方案")
        val result = tool.executeWithContext(args("设计一个方案"), ctx("s1")) as ToolResult.Success
        val hint = result.data.jsonObject["hint"]!!.jsonPrimitive.content
        assertTrue(hint.contains("已澄清"))
    }

    @Test
    fun clarified_flagTrackedPerSession() {
        tool.markClarified("s1", "设计一个方案")
        assertTrue(tool.isClarified("s1", "设计一个方案"))
        assertFalse(tool.isClarified("s2", "设计一个方案"))
        assertFalse(tool.isClarified("s1", "后台编译"))
    }
}
