package com.R.codecore.feature.agent.domain.model

/**
 * 思考强度：映射到 OpenAI o 系模型的 reasoning_effort（low/medium/high）、
 * Anthropic extended thinking 的 budget_tokens、Gemini thinkingConfig 的预算/级别。
 */
enum class ReasoningEffort(val apiValue: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high")
}
