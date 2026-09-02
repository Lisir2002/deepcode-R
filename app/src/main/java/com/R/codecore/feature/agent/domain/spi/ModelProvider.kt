package com.R.codecore.feature.agent.domain.spi

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.flow.Flow

/**
 * 模型供应商适配层（从 DeepCore-Code 反哺迁移而来）。
 *
 * 各家协议差异巨大（Anthropic 的 tool_use block、OpenAI 的 tool_calls、
 * 各家 streaming 的 SSE 格式、prompt caching 的写法），但**这些差异必须烂在这一层**。
 * 对上只暴露统一的 [CompletionChunk] 流，Agent 主循环永远不需要知道
 * 自己调的是谁家的模型。
 *
 * 换模型 = 换一个实现，Runtime 一行不动。
 *
 * 与 deepcode-R 领域类型的边界：本 SPI 是**自洽**的（自带 ToolCall/ToolSpec），
 * 不引用 deepcode-R 的 domain.model / domain.tool，由 ModelProviderAdapter 在接入点
 * 负责双向转换（AgentTool → ToolSpec、本包 ToolCall → deepcode-R ToolCall）。
 */
interface ModelProvider {

    /** 稳定 ID，写进 ModelRef.providerId，用于序列化后还原。 */
    val id: String

    val displayName: String

    suspend fun listModels(): List<ModelInfo> = emptyList()

    /** 是否支持某模型（用于运行时路由：主模型 / 压缩用小模型）。 */
    fun supports(modelId: String): Boolean = true

    /**
     * 测试某模型的连通性：对该模型发一条极短最小请求，
     * 返回耗时与结果。各协议实现应复用本 Provider 的鉴权与端点逻辑；未实现时默认报失败。
     */
    suspend fun testModel(modelId: String): ModelTestResult =
        ModelTestResult(success = false, latencyMs = 0, message = "此协议未实现连通性测试")

    fun stream(request: CompletionRequest): Flow<CompletionChunk>
}

/** 单次模型连通性测试的结果（供设置页「测试」按钮展示）。 */
data class ModelTestResult(
    val success: Boolean,
    val latencyMs: Long,
    val message: String,
)

data class ModelInfo(
    val id: String,
    val displayName: String,
    val contextWindowTokens: Int,
    val maxOutputTokens: Int,
    val supportsTools: Boolean = true,
    val supportsThinking: Boolean = false,
    val supportsPromptCaching: Boolean = false,
    /** 每百万 token 输入价格（美元），用于成本显示。null 表示未知。 */
    val inputPricePerMToken: Double? = null,
    val outputPricePerMToken: Double? = null,
)

// ───────────────────────── 协议中立的消息表示 ─────────────────────────

enum class LlmRole { SYSTEM, USER, ASSISTANT, TOOL }

/** 多模态图片（供支持视觉的协议如 Gemini/OpenAI 使用）。 */
data class LlmImage(
    val mimeType: String,
    val base64Data: String,
)

/**
 * 与具体供应商无关的一条消息。
 * 工具结果统一以 role=TOOL + toolCallId 表达，由 Provider 实现翻译成各家协议。
 * [reasoning]/[signature] 承载思考模式回传（DeepSeek 思考 / Anthropic extended thinking 工具循坏必须），
 * [images] 承载多模态输入。
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String,
    val toolCallId: String? = null,
    val toolName: String? = null,
    /** assistant 消息里携带的工具调用请求。 */
    val toolCalls: List<ToolCall> = emptyList(),
    val reasoning: String? = null,
    val signature: String? = null,
    val images: List<LlmImage> = emptyList(),
)

/** 协议中立的模型引用：providerId + modelId。 */
data class ModelRef(
    val providerId: String,
    val modelId: String,
) {
    override fun toString(): String = "$providerId/$modelId"
}

/** 协议中立的 token 用量统计。 */
data class Usage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

/** 工具声明（JSON Schema 的 parameters 直达 function-calling）。由 Adapter 从 deepcode-R 的 AgentTool 映射。 */
data class ToolSpec(
    val name: String,
    val description: String,
    /** JSON Schema，交给 LLM 做 function calling。与 MCP inputSchema 同构。 */
    val parameters: JsonObject = JsonObject(emptyMap()),
)

/** LLM 发起的一次工具调用请求。 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonObject = JsonObject(emptyMap()),
)

/** 生成带可读前缀的 ID，方便日志里一眼看出是什么东西。 */
fun newToolCallId(): String = "call-${java.util.UUID.randomUUID()}"

data class CompletionRequest(
    val modelRef: ModelRef,
    val messages: List<LlmMessage>,
    val system: String? = null,
    val tools: List<ToolSpec> = emptyList(),
    val maxTokens: Int = 8192,
    val temperature: Double? = null,
    val stopSequences: List<String> = emptyList(),
    /** 开启提示缓存（若供应商支持）——手机上省钱省流量，务必支持。 */
    val enablePromptCaching: Boolean = true,
    /** 用于取消长请求；为 null 时无法取消。 */
    val turnId: String? = null,
)

/** 流式产出的增量。UI 最终看到的 AgentEvent 由 Runtime 从这里翻译而来。 */
sealed interface CompletionChunk {

    data class Thinking(val text: String) : CompletionChunk

    data class Text(val text: String) : CompletionChunk

    /** 一轮回复全部产出后，Anthropic extended thinking 的加密签名（thinking block 的 signature），
     *  供多轮/工具循环判定后原样回传。其他供应商不产出。 */
    data class Signature(val text: String) : CompletionChunk

    /** 一批工具调用（模型可能一次返回多个）。 */
    data class ToolCalls(val calls: List<ToolCall>) : CompletionChunk

    data class UsageUpdate(val usage: Usage) : CompletionChunk

    /** 正常结束，附带停止原因。 */
    data class Done(val reason: StopReasonRaw) : CompletionChunk

    data class Error(val message: String, val retryable: Boolean = false) : CompletionChunk
}

enum class StopReasonRaw { END_TURN, MAX_TOKENS, TOOL_USE, STOP_SEQUENCE }