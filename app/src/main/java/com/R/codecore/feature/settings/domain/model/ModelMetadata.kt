package com.R.codecore.feature.settings.domain.model

data class ModelMetadata(
    val id: String,
    val providerId: String? = null,
    val displayName: String = id,
    val contextTokens: Int,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val supportsTools: Boolean = false,
    val supportsVision: Boolean = false,
    val supportsReasoning: Boolean = false,
    val source: Source = Source.INFERRED,
    /**
     * RC63 兼容端点推断链路的审计信息：UI 上的智能预填 banner、来源徽章用它显示
     * 「当前支持（Vision/Tools/Reasoning）来自官方 catalog / 启发式匹配 / 兼容端点策略 / 用户手动覆盖」
     * 便于小白理解自己为什么被判定成支持/不支持。仅 INFERRED 源非 null，MODELS_DEV 源为 null。
     */
    val inferenceReason: InferenceReason? = null
) {
    enum class Source {
        MODELS_DEV,
        INFERRED
    }

    /**
     * 三能力支持决策的审计来源：方便设置页 banner/来源角标给小白解释"为什么这个模型被认为支持/不支持 Vision"。
     *
     * 决策链（从先到后覆盖）：
     *  1. probablyVision / probablyTools / probablyReasoning 启发式（由 ModelMetadataService.default() 填）；
     *  2. 兼容端点全局策略 DefaultPolicy（STRICT/HEURISTIC/LAX/MANUAL，填 appliedPolicy）；
     *  3. 单模型复选框手动覆盖（填 overrideVision/overrideTools/overrideReasoning = true）。
     *
     * 每一步覆盖都保留原始字段，便于 UI 显示「自动推荐」和「用户改了什么」。
     */
    data class InferenceReason(
        val byProbablyVision: Boolean = false,
        val byProbablyTools: Boolean = false,
        val byProbablyReasoning: Boolean = false,
        val appliedPolicy: String? = null,
        val overrideVision: Boolean? = null,
        val overrideTools: Boolean? = null,
        val overrideReasoning: Boolean? = null
    )
}

