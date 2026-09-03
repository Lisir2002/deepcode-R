package com.core.deepcode.feature.settings.data.local.entity

data class AIProviderEntity(
     val id: String,
    val name: String,
    val type: String,
    /**
     * Android Keystore 加密后的 API Key（AES-256-GCM）。
     * RC68 SCHEMA 38 迁移：删除明文 apiKey 列，从此列唯一负责存储；若为空表示加密尚未执行。
     * RC91：声明与迁移 31/38 一致的 SQL DEFAULT ''，避免 Room TableInfo 校验失败。
     */
    
    val encryptedApiKey: String = "",
    val baseUrl: String,
    /**
     * 历史遗留名：AIProviderConfig.toDomain 的 selectedModel 分支永远返回 `ifBlank { defaultModel }`。
     * 现在统一：删除单独的 selectedModel 冗余列，`defaultModel` 直接重命名语义为「当前选中的模型」（等价 UI 上的 selectedModel）。
     * 名字保留 defaultModel 以避免迁移脚本对老数据的大规模重写（RC68 只做列类型/约束/空值的一致化，不重命名字段，减少迁移风险）。
     */
    val defaultModel: String,
    /**
     * 互斥激活（互斥 true）：全局最多 1 行 =1。
     * （DB-SHIELD-RC68 P0-1：由 AIProviderRepositoryImpl 的 saveProvider/setActiveProvider 通过先 deactivateAllProviders 清所有行再 insert 的仓储级事务保证。）
     */
    val isActive: Boolean,
    /** 可用模型列表，以换行分隔持久化。 */
    
    val models: String = "",
    /**
     * 布尔语义：该 provider 是否在切换列表里“勾选可用”（= 灰色打钩）。
     * 与 isActive 的差异明确化：
     *   - isActive 互斥：哪一条是当前正在调用模型接口的。
     *   - isEnabled 复选：用户不希望出现在「切换模型下拉」里的 provider 可以关掉。
     * RC68 前的 bug：UI 代码里有路径把两个字段都写成 true；仓储 invariant 修复确保不会同时让两条 active。
     */
    
    val isEnabled: Boolean = true,
    
    val useFullUrl: Boolean = false,
    
    val useResponseApi: Boolean = false
)
