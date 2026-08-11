package com.deep.rcode.feature.t2i.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * T2I Provider 下的某个具体模型及其能力元数据。
 * 一个 provider（OPENAI_COMPATIBLE）通常支持多个模型（dall-e-3 / dall-e-2 等），
 * 能力维度与 LLM 版 [com.deep.rcode.feature.settings.domain.model.ModelMetadata] 对齐，
 * 但换成文生图专用的 supportsHd / supportsInpaint / defaultWidth 等字段。
 */
@Entity(tableName = "t2i_provider_models")
data class T2IProviderModelEntity(
    @PrimaryKey val id: String,
    /** 外键 → [T2IProviderEntity.id]。 */
    val providerId: String,
    /** 传给 Provider API 的模型 ID（如 "dall-e-3"）。 */
    val modelId: String,
    /** UI 上显示的友好名称，为空则回退为 modelId。 */
    val displayName: String = "",
    val supportsHd: Boolean = false,
    val supportsInpaint: Boolean = false,
    val defaultWidth: Int = 1024,
    val defaultHeight: Int = 1024,
    /** 该模型允许的最大 step（扩散步数）。 */
    val maxSteps: Int = 50,
    /** 默认 step 值（UI 预填/AI 没指定 steps 时用）。 */
    val defaultSteps: Int = 30,
    /** 每张图消耗的 token 额度（由权限策略引擎 P4/P5 扣减用），0 表示不消耗。 */
    val costPerImageTokens: Int = 0,
    val createdAtMs: Long = 0,
    val updatedAtMs: Long = 0
)
