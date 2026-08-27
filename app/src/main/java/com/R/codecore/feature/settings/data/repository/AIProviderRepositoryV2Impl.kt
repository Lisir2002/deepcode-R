package com.R.codecore.feature.settings.data.repository

import com.R.codecore.core.security.CredentialEncryptor
import com.R.codecore.core.util.FileLogger
import com.R.codecore.core.util.EnumSafe
import com.R.codecore.datalayer.repository.SettingsRepository as V2SettingsRepository
import com.R.codecore.feature.settings.domain.model.AIProviderConfig
import com.R.codecore.feature.settings.domain.model.ProviderType
import com.R.codecore.feature.settings.domain.repository.AIProviderRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AIProvider 仓库 V2 实现（v2-full-takeover P2-1 批 1：settings 域切 V2 读源）。
 *
 * 语义与 Room 版完全对齐（RC68 P0-1 active 互斥 / RC71 加密失败中止 / RC68 SCHEMA 38 只写密文列）：
 *  - 读：V2 observeProviders() / observeActiveProvider()（Flow 响应式，P0-1 补齐）；
 *  - 写：V2 saveProvider() 内含「isActive 时先 deactivateAll」事务化（比 Room 版的两步非原子更强）；
 *  - 加密：保留 CredentialEncryptor 列级加解密唯一入口，失败抛异常中止保存。
 */
@Singleton
class AIProviderRepositoryV2Impl @Inject constructor(
    private val v2: V2SettingsRepository,
    private val encryptor: CredentialEncryptor,
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepoV2"
    }

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return v2.observeProviders().map { rows -> rows.map { it.toDomainModel() } }
    }

    override fun getActiveProvider(): Flow<AIProviderConfig?> {
        return v2.observeActiveProvider().map { it?.toDomainModel() }
    }

    override suspend fun getActiveProviderSync(): AIProviderConfig? {
        return v2.getActiveProvider()?.toDomainModel()
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? {
        return v2.getProvider(id)?.toDomainModel()
    }

    override suspend fun saveProvider(provider: AIProviderConfig) {
        FileLogger.i(TAG, "保存提供商 id=${provider.id} name=${provider.name} active=${provider.isActive} enabled=${provider.isEnabled}")
        // RC71：先取已存在的密文，供「用户未改 API Key」时保留，避免空串覆盖。
        val existingEncrypted = runCatching {
            v2.getProvider(provider.id)?.encrypted_api_key ?: ""
        }.getOrDefault("")
        val encrypted = encryptApiKeyOrThrow(provider.apiKey, existingEncrypted)
        // V2 saveProvider 内含 RC68 P0-1 不变量（isActive 时先 deactivateAll），且为单事务。
        v2.saveProvider(
            id = provider.id,
            name = provider.name,
            type = provider.type.name,
            encryptedApiKey = encrypted,
            baseUrl = provider.baseUrl,
            defaultModel = provider.selectedModel.ifBlank { provider.defaultModel },
            isActive = provider.isActive,
            models = provider.models.joinToString("\n"),
            isEnabled = provider.isEnabled,
            useFullUrl = provider.useFullUrl,
            useResponseApi = provider.useResponseApi,
        )
    }

    override suspend fun deleteProvider(id: String) {
        FileLogger.i(TAG, "删除提供商 id=$id")
        v2.deleteProvider(id)
    }

    override suspend fun setActiveProvider(id: String) {
        FileLogger.i(TAG, "切换启用提供商 id=$id")
        v2.setActiveProvider(id)
    }

    override suspend fun setSelectedModel(id: String, model: String) {
        FileLogger.i(TAG, "切换模型 provider=$id model=$model")
        v2.setDefaultModel(id, model)
    }

    override suspend fun updateModels(id: String, models: List<String>) {
        FileLogger.d(TAG, "更新模型列表 provider=$id 共 ${models.size} 个")
        v2.setModels(id, models.joinToString("\n"))
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        FileLogger.i(TAG, "设置提供商显示启用开关 provider=$id isEnabled=$isEnabled")
        v2.setProviderEnabled(id, isEnabled)
    }

    override suspend fun ensureActiveProvider() {
        if (v2.getActiveProvider() != null) return
        val first = v2.listProviders().firstOrNull() ?: return
        FileLogger.i(TAG, "无激活提供商，自动激活首个: ${first.id} (${first.name})")
        v2.setActiveProvider(first.id)
    }

    /**
     * RC71：apiKey 非空 → 必须加密成功，失败抛异常中止保存（绝不写空串覆盖已有密文）；
     * apiKey 为空但已有密文 → 保留已有密文；都无 → 空串。
     */
    private fun encryptApiKeyOrThrow(apiKey: String, existingEncrypted: String): String = when {
        apiKey.isNotBlank() -> {
            runCatching { encryptor.encrypt(apiKey) }
                .onFailure {
                    FileLogger.e(TAG, "加密 apiKey 失败，中止保存（避免覆盖已有密文）: ${it.message}", it)
                    throw IllegalStateException("API Key 加密失败，保存已中止: ${it.message}", it)
                }
                .getOrThrow()
        }
        existingEncrypted.isNotBlank() -> existingEncrypted
        else -> ""
    }

    /** 只从密文列解密；解密失败 → 空串 + 日志，不崩 UI（RC68 SCHEMA 38）。 */
    private fun decryptApiKey(encryptedApiKey: String): String {
        if (encryptedApiKey.isEmpty()) return ""
        return runCatching { encryptor.decrypt(encryptedApiKey) }
            .onFailure { FileLogger.w(TAG, "解密 apiKey 失败，返回空串: ${it.message}") }
            .getOrDefault("")
    }

    private fun com.R.codecore.datalayer.sqldelight.settings.Ai_providers.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        return AIProviderConfig(
            id = id,
            name = name,
            type = EnumSafe.valueOf(type, ProviderType.OPENAI, tag = "V2 Ai_providers.type"),
            apiKey = decryptApiKey(encrypted_api_key),
            baseUrl = base_url,
            defaultModel = default_model,
            isActive = is_active == 1L,
            models = modelList,
            // RC68 SCHEMA 38：selectedModel 冗余概念已合并进 defaultModel（UI 上同一语义）。
            selectedModel = default_model,
            isEnabled = is_enabled == 1L,
            useFullUrl = use_full_url == 1L,
            useResponseApi = use_response_api == 1L,
        )
    }
}
