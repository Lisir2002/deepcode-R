package com.R.codecore.feature.t2i.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * T2I（Text-to-Image 文生图）Provider 实体。
 * 与 [com.R.codecore.feature.settings.data.local.entity.AIProviderEntity] 概念对齐但独立建表：
 * LLM 接口与 T2I 接口的契约（请求体 shape、响应体 shape、同步/异步形态、鉴权头）完全不同，
 * 强行复用同一张表的 type 字段区分会导致 defaultModel / models / useResponseApi 等
 * LLM 专属列语义污染，因此 T2I 独立 3 张表。
 */
@Entity(
    tableName = "t2i_providers",
    indices = [
        // RC91：与迁移 39 创建的 index_t2i_providers_isActive 对齐
        Index(value = ["isActive"]),
        // 与迁移 39 创建的 index_t2i_providers_priority（DESC）对齐
        Index(value = ["priority"], orders = [Index.Order.DESC])
    ]
)
data class T2IProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    /** provider 类型：OPENAI_COMPATIBLE | ANTHROPIC | GEMINI | STABLE_DIFFUSION | CUSTOM */
    val type: String,
    val baseUrl: String,
    /** Android Keystore + AES-256-GCM 加密后的 API Key，空字符串表示尚未加密。 */
    @ColumnInfo(defaultValue = "''")
    val encryptedApiKey: String = "",
    /** SYNC | ASYNC | AUTO。AUTO 由 ImageGenerator 首次调用时探测并缓存回写。 */
    @ColumnInfo(defaultValue = "'AUTO'")
    val endpointMode: String = "AUTO",
    /** 互斥激活：全局最多 1 行 = true，由 T2IRepository 仓储级事务保证。 */
    @ColumnInfo(defaultValue = "0")
    val isActive: Boolean = false,
    /** 多 provider failover 排序用，数字越大优先级越高。 */
    @ColumnInfo(defaultValue = "0")
    val priority: Int = 0,
    /** 是否在 UI 切换列表里勾选可用（复选，非互斥）。 */
    @ColumnInfo(defaultValue = "1")
    val isEnabled: Boolean = true,
    /** 额外自定义请求头，JSON Object 字符串（k→v 映射），留空表示无。 */
    @ColumnInfo(defaultValue = "''")
    val extraHeadersJson: String = "",
    @ColumnInfo(defaultValue = "0")
    val createdAtMs: Long = 0,
    @ColumnInfo(defaultValue = "0")
    val updatedAtMs: Long = 0
)
