package com.deep.rcode.feature.settings.data.repository

import com.deep.rcode.core.security.CredentialEncryptor
import com.deep.rcode.core.util.FileLogger
import com.deep.rcode.feature.settings.data.local.dao.AIProviderDao
import com.deep.rcode.feature.settings.data.local.entity.AIProviderEntity
import com.deep.rcode.feature.settings.domain.model.AIProviderConfig
import com.deep.rcode.feature.settings.domain.model.ProviderType
import com.deep.rcode.feature.settings.domain.repository.AIProviderRepository
import com.deep.rcode.core.util.EnumSafe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AIProviderRepositoryImpl @Inject constructor(
    private val aiProviderDao: AIProviderDao,
    private val encryptor: CredentialEncryptor
) : AIProviderRepository {

    private companion object {
        const val TAG = "AIProviderRepo"
    }

    override fun getAllProviders(): Flow<List<AIProviderConfig>> {
        return aiProviderDao.getAllProviders().map { entities ->
            entities.map { it.toDomainModel() }
        }
    }

    override fun getActiveProvider(): Flow<AIProviderConfig?> {
        return aiProviderDao.getActiveProvider().map { it?.toDomainModel() }
    }

    override suspend fun getActiveProviderSync(): AIProviderConfig? {
        return aiProviderDao.getActiveProviderSync()?.toDomainModel()
    }

    override suspend fun getProviderById(id: String): AIProviderConfig? {
        return aiProviderDao.getProviderById(id)?.toDomainModel()
    }

    override suspend fun saveProvider(provider: AIProviderConfig) {
        FileLogger.i(TAG, "保存提供商 id=${provider.id} name=${provider.name} active=${provider.isActive} enabled=${provider.isEnabled}")
        // RC71：先取已存在的密文，供 toEntity 在「用户未改 API Key」时保留，避免空串覆盖。
        val existingEncrypted = runCatching {
            aiProviderDao.getProviderById(provider.id)?.encryptedApiKey ?: ""
        }.getOrDefault("")
        val entity = provider.toEntity(existingEncrypted)
        // DB-SHIELD-RC68 P0-1 invariant 修复：
        //   saveProvider 传入 isActive=true 的行时，先把所有行的 isActive 清 0（deactivateAllProviders）
        //   再 insert = 这一行。保证 DB 中 isActive=1 的行数永远最多 1 行（互斥 active）。
        //   之前：setActiveProvider 正确做了「先 deactivateAllProviders + activateProvider」，
        //         但 saveProvider 走的是另一条路径（首次添加 Provider 时直接 insert），
        //         如果 UI 连点或者用户修改某条 Provider 时误把 active=true 一起传，
        //         就会出现 2 条都 active 的脏数据 → getActiveProvider 返回第一条（不确定是哪条）
        //         → 切换模型下拉偶尔回跳到旧 provider。
        if (provider.isActive) {
            aiProviderDao.deactivateAllProviders()
        }
        aiProviderDao.insertProvider(entity)
    }

    override suspend fun deleteProvider(id: String) {
        FileLogger.i(TAG, "删除提供商 id=$id")
        aiProviderDao.deleteProvider(id)
    }

    override suspend fun setActiveProvider(id: String) {
        FileLogger.i(TAG, "切换启用提供商 id=$id")
        aiProviderDao.deactivateAllProviders()
        aiProviderDao.activateProvider(id)
    }

    override suspend fun setSelectedModel(id: String, model: String) {
        FileLogger.i(TAG, "切换模型 provider=$id model=$model")
        // RC68 SCHEMA 38：selectedModel 冗余列已删除，defaultModel 列就是「当前选中的模型」。
        aiProviderDao.setDefaultModel(id, model)
    }

    override suspend fun updateModels(id: String, models: List<String>) {
        FileLogger.d(TAG, "更新模型列表 provider=$id 共 ${models.size} 个")
        aiProviderDao.setModels(id, models.joinToString("\n"))
    }

    override suspend fun setProviderEnabled(id: String, isEnabled: Boolean) {
        FileLogger.i(TAG, "设置提供商显示启用开关 provider=$id isEnabled=$isEnabled")
        aiProviderDao.setProviderEnabled(id, isEnabled)
    }

    override suspend fun ensureActiveProvider() {
        if (aiProviderDao.getActiveProviderSync() != null) return
        val first = aiProviderDao.getAllProviders().first().firstOrNull() ?: return
        FileLogger.i(TAG, "无激活提供商，自动激活首个: ${first.id} (${first.name})")
        aiProviderDao.deactivateAllProviders()
        aiProviderDao.activateProvider(first.id)
    }

    /**
     * RC68 SCHEMA 38：明文 apiKey 列已 DROP，只从 encryptedApiKey 解密。
     * 解密失败 → 空串（避免 UI 崩）+ FileLogger。
     */
    private suspend fun AIProviderEntity.resolveApiKey(): String {
        if (encryptedApiKey.isEmpty()) return ""
        return runCatching { encryptor.decrypt(encryptedApiKey) }
            .onFailure { FileLogger.w(TAG, "解密 apiKey 失败，返回空串: ${it.message}") }
            .getOrDefault("")
    }

    private suspend fun AIProviderEntity.toDomainModel(): AIProviderConfig {
        val modelList = models.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val currentModel = defaultModel
        return AIProviderConfig(
            id = id,
            name = name,
            type = EnumSafe.valueOf(type, ProviderType.OPENAI, tag = "AIProviderEntity.type"),
            apiKey = resolveApiKey(),
            baseUrl = baseUrl,
            defaultModel = currentModel,
            isActive = isActive,
            models = modelList,
            // RC68 SCHEMA 38：selectedModel 冗余列已删除，直接与 defaultModel 合并（UI 上同一语义）。
            selectedModel = currentModel,
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi
        )
    }

    /**
     * RC68 SCHEMA 38：只写 encryptedApiKey（明文 apiKey 列已删除，Entity 无此字段）。
     *
     * RC71 严重 bug 修复（API Key 保存后退回即被清空）：
     * 旧实现 `runCatching { encryptor.encrypt(apiKey) }.getOrDefault("")` 在加密失败时
     * 会静默落成空串，而 insertProvider 用 OnConflictStrategy.REPLACE 覆盖整行 →
     * 任何加密临时失败（如 ensureInitialized 未成功导致 requireDek 抛异常）都会把
     * 用户输入/已保存的 API Key 永久清空，且无法恢复。
     *
     * 新逻辑：
     *  - apiKey 非空（用户输入了新 key）→ 必须加密成功，失败则抛异常中止保存，
     *    绝不把空串写入库覆盖已有密文；
     *  - apiKey 为空但已有密文（编辑时未改 key）→ 保留已有密文不覆盖；
     *  - apiKey 为空且无已有密文（新建未填）→ 空串。
     */
    private suspend fun AIProviderConfig.toEntity(existingEncrypted: String): AIProviderEntity {
        val encrypted = when {
            apiKey.isNotBlank() -> {
                runCatching { encryptor.encrypt(apiKey) }
                    .onFailure {
                        FileLogger.e(TAG, "加密 apiKey 失败，中止保存（避免覆盖已有密文）: ${it.message}", it)
                        throw IllegalStateException("API Key 加密失败，保存已中止: ${it.message}", it)
                    }
                    .getOrThrow()
            }
            existingEncrypted.isNotBlank() -> existingEncrypted // RC71：未改 key，保留已有密文
            else -> ""
        }
        // RC68 selectedModel 合并：当 selectedModel 非 blank 时用它写入 defaultModel，否则用域里的 defaultModel。
        val mergedModel = selectedModel.ifBlank { defaultModel }
        return AIProviderEntity(
            id = id,
            name = name,
            type = type.name,
            encryptedApiKey = encrypted,
            baseUrl = baseUrl,
            defaultModel = mergedModel,
            isActive = isActive,
            models = models.joinToString("\n"),
            isEnabled = isEnabled,
            useFullUrl = useFullUrl,
            useResponseApi = useResponseApi
        )
    }
}
