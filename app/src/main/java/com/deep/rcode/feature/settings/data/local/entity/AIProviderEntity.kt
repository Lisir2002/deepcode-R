package com.deep.rcode.feature.settings.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "ai_providers")
data class AIProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val type: String,
    /** 明文 Room，已由 [encryptedApiKey] 替代。保留字段用于迁移过渡。 */
    val apiKey: String,
    /** Android Keystore 加密后的 API Key（AES-256-GCM）。优先使用此字段。 */
    val encryptedApiKey: String = "",
    val baseUrl: String,
    val defaultModel: String,
    val isActive: Boolean,
    /** 可用模型列表，以换行分隔持久化。 */
    val models: String = "",
    /** 当前选中模型；为空时回退到 defaultModel。 */
    val selectedModel: String = "",
    val isEnabled: Boolean = true,
    val useFullUrl: Boolean = false,
    val useResponseApi: Boolean = false
)
