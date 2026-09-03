package com.core.deepcode.core.security

/**
 * ZTH 跨设备共享密钥（C.4.18 方案 C：完整 Firestore 双向同步）。
 *
 * ### Phase 1.5 设计约束（Preflight P12 纠正）
 * 项目目前未引入：
 *  - Argon2id KDF 库（如 argon2-jvm / lazysodium）
 *  - BIP-39 助记词库（如 bitcoinj / cashlib）
 *  - Firebase/Firestore BOM SDK
 *  因此 **Phase 1 只定义接口契约和占位数据结构**，**Phase 4 才加 Gradle 依赖**并实现：
 *
 *    a) build.gradle.kts 加 implementation(platform("com.google.firebase:firebase-bom:..."))
 *    b) 加 argon2 / BIP-39 纯 Kotlin 依赖
 *    c) 写 RealZthSharedSyncKeyStore：用户口令 → Argon2id(m=64MB,t=3,p=1) → 512-bit 派生
 *       → 256-bit 截断为 shared_sync_key（AES-GCM key）+ 256-bit 截断为 BIP-39 熵
 *       → BIP-39 English 词库出 8 词助记词，校验和 4 bit。
 *
 * ### 安全不变性（C.4.18 SYNC-INV-1）
 * - shared_sync_key **永远不上 Firestore / 不进任何网络 SDK**。
 * - Firestore 里的 `ct` 列 = AES-GCM(shared_sync_key, 本地 Entity↔FirestoreDto JSON)
 *   + `hmac` 列 = HMAC-SHA256(shared_sync_key, ct) 防篡改。
 * - 8 词助记词只显示一次，用户手抄；App 不持久化助记词本身（只持久化派生过程）。
 */
interface ZthSharedSyncKeyStore {

    /** Phase 4 真实实现：用户输入口令 + 确认口令（一致）→ 生成助记词并显示 1 次。 */
    sealed interface GenerateResult {
        data class Success(val bip39Mnemonic8Words: List<String>) : GenerateResult
        data class PasswordMismatch(val reason: String) : GenerateResult
        /** Phase 1 占位：依赖未加载。 */
        object DependencyNotReady : GenerateResult
    }

    /** Phase 4 真实实现：用户输入 8 词助记词 → 反向转回 shared_sync_key。 */
    sealed interface ImportResult {
        data class Success(val restored: Boolean) : ImportResult
        data class InvalidMnemonic(val reason: String) : ImportResult
        object DependencyNotReady : ImportResult
    }

    suspend fun generateSharedKey(userPassword: CharArray, confirmPassword: CharArray): GenerateResult

    suspend fun importFromMnemonic(mnemonicWords: List<String>): ImportResult

    /** Phase 1 占位：任何时候调用都抛 DependencyNotReady → Phase 4 真实现覆写。 */
    fun isReady(): Boolean = false
}

/**
 * Phase 1 临时实现：保证依赖图编译通过（Phase 2+ 不引用 Firestore），Phase 4 替换为 RealZthSharedSyncKeyStore。
 * 所有方法返回 DependencyNotReady；UI 层接收到则显示「跨设备同步：Phase 4 后激活」。
 */
class PlaceholderZthSharedSyncKeyStore : ZthSharedSyncKeyStore {
    override suspend fun generateSharedKey(
        userPassword: CharArray,
        confirmPassword: CharArray
    ): ZthSharedSyncKeyStore.GenerateResult = ZthSharedSyncKeyStore.GenerateResult.DependencyNotReady

    override suspend fun importFromMnemonic(
        mnemonicWords: List<String>
    ): ZthSharedSyncKeyStore.ImportResult = ZthSharedSyncKeyStore.ImportResult.DependencyNotReady
}
