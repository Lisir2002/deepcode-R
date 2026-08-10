package com.deep.rcode.feature.agent.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * RC63 备选方案④：单个模型级别的「多模态识图 / 工具调用 / 深度思考」三能力复选框手动覆盖。
 *
 * 主键 = providerType（ProviderType.name 字符串）+ modelId，确保不同 provider 下同名模型
 * （如 openai/gpt-4o 和 groq/gpt-4o）能分开配置，不互相串配置。
 *
 * 三个覆盖字段类型为 Boolean?：null 表示「用户没动，继续用启发式 / 兼容端点策略」；
 * true/false 表示用户明确覆盖为开/关。这和设计里「④ 优先级最高但只覆盖用户点了的字段」一致。
 */
@Entity(tableName = "model_capability_overrides")
data class ModelCapabilityOverrideEntity(
    @PrimaryKey(autoGenerate = false)
    val id: String,
    /** ProviderType.name；使用字符串避免 Room 存储枚举的兼容性坑。 */
    val providerType: String,
    val modelId: String,
    val overrideVision: Boolean? = null,
    val overrideTools: Boolean? = null,
    val overrideReasoning: Boolean? = null,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    companion object {
        /** 主键合成方法，UI 层和 Dao 共用避免写错。 */
        fun composeId(providerType: String, modelId: String): String = "$providerType:$modelId"
    }
}
