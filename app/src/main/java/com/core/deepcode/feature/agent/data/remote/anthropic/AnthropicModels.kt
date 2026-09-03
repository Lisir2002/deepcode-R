package com.core.deepcode.feature.agent.data.remote.anthropic

data class AnthropicMessageRequest(
    val model: String,
    val messages: List<AnthropicMessage>,
    val system: String? = null,
    val max_tokens: Int = 16384,
    // 开启 extended thinking 时不能携带 temperature（官方要求），置 null 由 Gson 跳过该字段。
    val temperature: Float? = null,
    val thinking: AnthropicThinkingConfig? = null,
    val tools: List<AnthropicToolDefinition>? = null,
    val stream: Boolean = false
)

/** Anthropic extended thinking 配置：type="enabled" + budget_tokens（思考 token 预算）。 */
data class AnthropicThinkingConfig(
    val type: String = "enabled",
    val budget_tokens: Int
)

data class AnthropicMessage(
    val role: String, // "user" or "assistant"
    val content: Any // Can be String or List<AnthropicContentBlock>
)

data class AnthropicContentBlock(
    val type: String, // "text", "tool_use", "tool_result", "thinking"
    val text: String? = null,
    val source: Map<String, Any>? = null,
    val id: String? = null, // for tool_use
    val name: String? = null, // for tool_use
    val input: Map<String, Any>? = null, // for tool_use
    val tool_use_id: String? = null, // for tool_result
    val content: String? = null, // for tool_result
    val is_error: Boolean? = null, // for tool_result
    val thinking: String? = null, // for thinking block：思考摘要文本
    val signature: String? = null // for thinking block：加密签名，多轮/工具循环须原样回传
)

data class AnthropicToolDefinition(
    val name: String,
    val description: String,
    val input_schema: Map<String, Any>
)

data class AnthropicMessageResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContentBlock>,
    val model: String,
    val stop_reason: String?,
    val stop_sequence: String?,
    val usage: AnthropicUsage
)

data class AnthropicUsage(
    val input_tokens: Int,
    val output_tokens: Int
)
