package com.R.codecore.feature.agent.data.local.entity

import androidx.room.Entity

/**
 * RC63 备选方案④：单个模型级别的「多模态识图 / 工具调用 / 深度思考」三能力复选框手动覆盖。
 *
 * RC68 SCHEMA 38：改为原生复合主键 (providerType, modelId)（@Entity primaryKeys）。
 * id 字段保留（兼容旧 DAO / UI 的 composeId() 代码），但不再是 @PrimaryKey。
 *
 * 三个覆盖字段类型为 Boolean?：null 表示「用户没动，继续用启发式 / 兼容端点策略」；
 * true/false 表示用户明确覆盖为开/关。
 */
@Entity(
    tableName = "model_capability_overrides",
    primaryKeys = ["providerType", "modelId"]
)
data class ModelCapabilityOverrideEntity(
    /** 保留用于兼容：UI 层和 Dao 仍会调用 `composeId`。RC68 后仅作冗余字段（用于日志/调试）。 */
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
