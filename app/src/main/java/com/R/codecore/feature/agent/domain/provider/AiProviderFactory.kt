package com.R.codecore.feature.agent.domain.provider

import com.R.codecore.feature.agent.domain.spi.AnthropicConfig
import com.R.codecore.feature.agent.domain.spi.GeminiConfig
import com.R.codecore.feature.agent.domain.spi.ModelProviderConfig
import com.R.codecore.feature.agent.domain.spi.ModelProviderRegistry
import com.R.codecore.feature.agent.domain.spi.OpenAIConfig
import com.R.codecore.feature.settings.domain.model.AIProviderConfig
import com.R.codecore.feature.settings.domain.model.ProviderType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 独立 Provider 工厂：把 deepcode-R 的存储模型 [AIProviderConfig] 翻译成
 * 注册表化的新 SPI 类型化配置，经 [ModelProviderRegistry] 解析描述后实例化，
 * 再包一层 [ModelProviderAdapter] 回到 [AIProvider] 消费面。
 *
 * 这是"照搬 DeepCore-Code 注册表式 Provider 层"的接入点：
 * - 新增厂商 = 在 DefaultProviderRegistry 追加一条 [com.R.codecore.feature.agent.domain.spi.ModelProviderDescriptor]
 *   并在此补 type→providerId 映射，Workflow/SubAgentRunner 免改。
 * - 协议差异隔离在 Provider 实现；可变式 var 配置追回补到 Adapter 以兼容既有消费契约。
 * - 优先走新 SPI；`type` 无法识别或解析失败时由调用方决定（Workflow 抛错 / SubAgentRunner 返回 null）。
 *
 * 已知迁移取舍：新 SPI 以 OpenAIConfig/AnthropicConfig/GeminiConfig 的 prefix endpoint 语义
 * 为准（completionsUrl/messagesUrl 沿用 DeepCore），因此配置里的 useFullUrl/useResponseApi
 * 不再影响请求构造；对 deepcode-R 既有成型的 baseUrl 前缀配置无影响。
 */
@Singleton
class AiProviderFactory @Inject constructor(
    private val registry: ModelProviderRegistry,
) {
    /**
     * 为 [config] 创建一个全新的、独立的 [AIProvider] 实例（Workflow 的识图回退 / 压缩、
     * SubAgentRunner 的子代理均调用它分别 new 实例，互不共享可变状态）。
     */
    fun create(config: AIProviderConfig, sessionId: String?): AIProvider {
        val typed: ModelProviderConfig = when (config.type) {
            ProviderType.ANTHROPIC -> AnthropicConfig().with(config)
            ProviderType.GEMINI -> GeminiConfig().with(config)
            else -> OpenAIConfig().with(config)
        }
        val descriptor = registry.resolve(typed.providerId)
            ?: throw IllegalStateException("未知 ProviderType: ${config.type}")
        val provider = descriptor.instantiate(typed)
        return ModelProviderAdapter(provider).also {
            it.apiKey = config.apiKey
            it.baseUrl = config.baseUrl
            it.model = config.effectiveModel
            it.useFullUrl = config.useFullUrl
            it.useResponseApi = config.useResponseApi
            it.logSessionId = sessionId
        }
    }

    /** 把 AIProviderConfig 的通用字段落到对应类型化配置上。 */
    private fun AnthropicConfig.with(c: AIProviderConfig) = copy(baseUrl = c.baseUrl, apiKey = c.apiKey, model = c.effectiveModel)
    private fun GeminiConfig.with(c: AIProviderConfig) = copy(baseUrl = c.baseUrl, apiKey = c.apiKey, model = c.effectiveModel)
    private fun OpenAIConfig.with(c: AIProviderConfig) = copy(baseUrl = c.baseUrl, apiKey = c.apiKey, model = c.effectiveModel)
}