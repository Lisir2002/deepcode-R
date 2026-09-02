package com.R.codecore.feature.agent.domain.provider

import com.R.codecore.feature.agent.domain.model.AgentImage
import com.R.codecore.feature.agent.domain.model.AgentMessage
import com.R.codecore.feature.agent.domain.tool.AgentTool
import com.R.codecore.feature.agent.domain.tool.ToolCall as DomainToolCall
import com.R.codecore.feature.agent.domain.spi.CompletionChunk
import com.R.codecore.feature.agent.domain.spi.CompletionRequest
import com.R.codecore.feature.agent.domain.spi.LlmImage
import com.R.codecore.feature.agent.domain.spi.LlmMessage
import com.R.codecore.feature.agent.domain.spi.LlmRole
import com.R.codecore.feature.agent.domain.spi.ModelProvider
import com.R.codecore.feature.agent.domain.spi.ModelRef
import com.R.codecore.feature.agent.domain.spi.ToolSpec
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 适配层：把「注册表化」的新 SPI [ModelProvider] 桥接回 deepcode-R 既有的 [AIProvider] 消费面。
 *
 * 为什么要这一层：
 * - deepcode-R 的 StatefulAgentWorkflow / ContextCompactor / SubAgentRunner 等大量调用点
 *   依赖 [AIProvider] (complete / completeStream + var 可变配置)。直接全部改点成本高、
 *   回归风险大。保留 [AIProvider] 作为稳定的领域消费契约，由本 Adapter 把新 SPI 接进来，
 *   现有调用点一行不动即可接入注册表化 / 不可变配置。
 * - 双向往返转换集中在 <b>这里</b>：AgentMessage↔LlmMessage、AgentTool↔ToolSpec、
 *   SPI ToolCall ↔ deepcode-R ToolCall。未来新厂商只改注册表，不碰本文件。
 *
 * 已知取舍（迁移期记录，待 deepcode-R 本地验证补齐）：
 * 1. Anthropic <b>signature</b>（extended thinking 工具循环回传）SPI 的 CompletionChunk
 *    暂不产出，多轮工具循环时 signature 可能为空 → 需在 Provider 层补充。
 * 2. reasoningEffort（DeepSeek/OpenAI 思考强度）未映射到 CompletionRequest。
 * 3. 图片已承载（LlmMessage.images），三家 Provider 的多模态渲染需逐个验证。
 */
class ModelProviderAdapter(
    private val provider: ModelProvider,
) : AIProvider {

    override var apiKey: String = ""
    override var baseUrl: String = ""
    override var useFullUrl: Boolean = false
    override var useResponseApi: Boolean = false
    override var model: String = ""
    override var logSessionId: String? = null

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): AIResponse {
        var content = ""
        val contentSb = StringBuilder()
        var reasoningSb = StringBuilder()
        var signature: String? = null
        val toolCalls = mutableListOf<DomainToolCall>()
        var usage = com.R.codecore.feature.agent.domain.spi.Usage()
        provider.stream(buildRequest(systemPrompt, messages, tools)).collect { chunk ->
            when (chunk) {
                is CompletionChunk.Text -> contentSb.append(chunk.text)
                is CompletionChunk.Thinking -> reasoningSb.append(chunk.text)
                is CompletionChunk.ToolCalls -> toolCalls += chunk.calls.map(::toDomainToolCall)
                is CompletionChunk.UsageUpdate -> usage = chunk.usage
                is CompletionChunk.Signature -> signature = chunk.text
                is CompletionChunk.Done -> Unit
                is CompletionChunk.Error -> throw IllegalStateException(chunk.message)
            }
        }
        return AIResponse(
            content = contentSb.toString(),
            toolCalls = toolCalls,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            reasoning = reasoningSb.toString().ifEmpty { null },
            signature = signature,
        )
    }

    override fun completeStream(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
        reasoningEffort: String?
    ): Flow<AIStreamChunk> {
        // collect + emit 到 sequence 不可行（Flow 需惰性）；用 channel / 直接 map 并聚合。
        // 这里用相对简单的实现：把 provider 流逐个翻译，并在 Done 时发出聚合的 Final。
        val finalHolder = AIResponseAccumulator()
        return provider.stream(buildRequest(systemPrompt, messages, tools)).map { chunk ->
            when (chunk) {
                is CompletionChunk.Text -> {
                    finalHolder.text.append(chunk.text)
                    AIStreamChunk.TextDelta(chunk.text)
                }
                is CompletionChunk.Thinking -> {
                    finalHolder.reasoning.append(chunk.text)
                    AIStreamChunk.ReasoningDelta(chunk.text)
                }
                is CompletionChunk.ToolCalls -> {
                    finalHolder.calls += chunk.calls.map(::toDomainToolCall)
                    // 工具调用增量没有 AIStreamChunk 对应物，静默累积，由 Final 统一吐出。
                    null
                }
                is CompletionChunk.Signature -> {
                    finalHolder.signature = chunk.text
                    null
                }
                is CompletionChunk.UsageUpdate -> {
                    finalHolder.usage = chunk.usage
                    null
                }
                is CompletionChunk.Done -> {
                    AIStreamChunk.Final(finalHolder.toResponse())
                }
                is CompletionChunk.Error -> throw IllegalStateException(chunk.message)
            }?.let { it } ?: null
        }.filterNotNull()
    }

    // ───────────────────────── 组装请求 ─────────────────────────

    private fun buildRequest(
        systemPrompt: String,
        messages: List<AgentMessage>,
        tools: List<AgentTool>,
    ): CompletionRequest = CompletionRequest(
        modelRef = ModelRef(provider.id, model.ifBlank { "demo-1" }),
        system = systemPrompt.takeIf { it.isNotBlank() },
        messages = messages.map(::toLlmMessage),
        tools = tools.map(::toToolSpec),
        maxTokens = 8192,
        turnId = logSessionId,
    )

    private fun toLlmMessage(msg: AgentMessage): LlmMessage = when (msg) {
        is AgentMessage.UserMessage -> LlmMessage(
            role = LlmRole.USER,
            content = msg.content,
            images = msg.images.map { LlmImage(it.mimeType, it.base64Data) },
        )

        is AgentMessage.AssistantMessage -> LlmMessage(
            role = LlmRole.ASSISTANT,
            content = msg.content,
            toolCalls = msg.toolCalls.map(toSpiToolCall),
            reasoning = msg.reasoning.ifBlank { null },
            signature = msg.signature.ifBlank { null },
        )

        is AgentMessage.ToolResultMessage -> LlmMessage(
            role = LlmRole.TOOL,
            content = msg.result,
            toolCallId = msg.id.ifBlank { msg.toolName },
            toolName = msg.toolName,
        )
    }

    private val toSpiToolCall: (DomainToolCall) -> com.R.codecore.feature.agent.domain.spi.ToolCall = { call ->
        com.R.codecore.feature.agent.domain.spi.ToolCall(
            id = call.id,
            name = call.name,
            arguments = if (call.arguments is JsonObject) call.arguments else JsonObject(call.arguments),
        )
    }

    private fun toDomainToolCall(call: com.R.codecore.feature.agent.domain.spi.ToolCall): DomainToolCall =
        DomainToolCall(id = call.id, name = call.name, arguments = call.arguments)

    private fun toToolSpec(tool: AgentTool): ToolSpec = ToolSpec(
        name = tool.name,
        description = tool.description,
        parameters = toJsonObject(tool.toJsonSchema()),
    )

    /** 递归把 JSON Schema 的 Map<String, Any> 转成 kotlinx JsonObject。 */
    private fun toJsonObject(map: Map<String, Any?>): JsonObject = buildJsonObject {
        map.forEach { (k, v) ->
            when (v) {
                is String -> put(k, v)
                is Boolean -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Double -> put(k, v)
                is Number -> put(k, v.toString())
                is Map<*, *> -> put(k, toJsonObject(v.mapKeys { it.key.toString() }))
                is List<*> -> {
                    val arr = JsonArray(v.map { toJsonElement(it) })
                    put(k, arr)
                }
                null -> put(k, "null")
                else -> put(k, v.toString())
            }
        }
    }

    private fun toJsonElement(any: Any?): JsonElement = when (any) {
        is String -> JsonPrimitive(any)
        is Boolean -> JsonPrimitive(any)
        is Int -> JsonPrimitive(any)
        is Long -> JsonPrimitive(any)
        is Double -> JsonPrimitive(any)
        is Number -> JsonPrimitive(any.toString())
        is Map<*, *> -> toJsonObject(any.mapKeys { it.key.toString() })
        is List<*> -> JsonArray(any.map { toJsonElement(it) })
        null -> JsonPrimitive("null")
        else -> JsonPrimitive(any.toString())
    }

    // ───────────────────────── 聚合器 ─────────────────────────

    private class AIResponseAccumulator {
        val text = StringBuilder()
        val reasoning = StringBuilder()
        val calls = mutableListOf<DomainToolCall>()
        var signature: String? = null
        var usage = com.R.codecore.feature.agent.domain.spi.Usage()

        fun toResponse(): AIResponse = AIResponse(
            content = text.toString(),
            toolCalls = calls,
            inputTokens = usage.inputTokens,
            outputTokens = usage.outputTokens,
            reasoning = reasoning.toString().ifEmpty { null },
            signature = signature,
        )
    }
}