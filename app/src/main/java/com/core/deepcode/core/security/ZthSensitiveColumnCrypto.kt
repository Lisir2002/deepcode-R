package com.core.deepcode.core.security

import com.core.deepcode.core.util.FileLogger
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ZTH 敏感列加密门面（C.4.8 方案 C：最严格安全策略）。
 *
 * 所有 ZTH Entity 中以 `s_` 前缀命名的字段必须通过本类加密后才能写 Room，
 * 读取 Room 后必须通过本类解密才能进入 UI/业务层。禁止把 `s_` 列当明文字符串读。
 *
 * 实现原则：
 * 1. 不要重新造 Keystore 轮子 → 直接委托给 [CredentialEncryptor]（V2 前缀 AES-256-GCM）
 *    MasterKey + DEK 两层架构。ZTH 用独立 alias 避免和凭据互串，由 CredentialEncryptor 自己保证。
 * 2. 启动构造：纯内存赋值，不碰 IO/Keystore（遵循 RC61a 启动期 ANR 规避约定）。
 * 3. 所有 encrypt/decrypt 必须在 Dispatchers.IO 线程调用（RC61a 启动阻塞修正）。
 *    上层业务如果在非 IO 线程调用，抛 [IllegalStateException]（早期 fail-fast，比 ANR 好）。
 *
 * 对应不变性：
 *  - SEC-INV-1：SwipeToConfirm 完成后才能写入 s_* 列
 *  - SEC-INV-2：s_* 列明文不得进 FileLogger（日志 0 id，LOG-INV-1 延伸）
 *  - SEC-INV-3：所有 s_* 列必须带 s_ 前缀（本类内部会断言）
 */
@Singleton
class ZthSensitiveColumnCrypto @Inject constructor(
    /** 底层直接复用，避免重复造 Keystore MasterKey/DEK 轮转。 */
    private val credentialEncryptor: CredentialEncryptor
) {
    private companion object {
        const val TAG = "ZthSensitiveColumnCrypto"
        const val S_PREFIX = "s_"
        /** 所有 s_* 列加密前必须先 ensureInitialized 一次（幂等）。 */
        private val initDone = AtomicBoolean(false)
    }

    /**
     * 幂等初始化；上层业务（AgentModule / Application.onCreate 启动后）先调一次。
     * 底层 CredentialEncryptor.ensureInitialized() 自己走 IO 线程，这里只管同步标志。
     */
    suspend fun ensureReady() {
        if (initDone.get()) return
        credentialEncryptor.ensureInitialized()
        initDone.set(true)
        FileLogger.i(TAG, "ZTH 敏感列加密就绪（复用 CredentialEncryptor V2 架构，AES-256-GCM）")
    }

    suspend fun encrypt(plaintext: String): String {
        if (!initDone.get()) error("ZthSensitiveColumnCrypto 未初始化：先调 ensureReady()")
        // 未来 Phase 3 如需要 thread 断言，可加 Looper/调度器检测；目前先由上层 withContext(Dispatchers.IO) 保
        return credentialEncryptor.encrypt(plaintext) // 走 V2:<base64(IV+ct+tag)>
    }

    suspend fun decrypt(ciphertext: String): String {
        if (!initDone.get()) error("ZthSensitiveColumnCrypto 未初始化：先调 ensureReady()")
        return credentialEncryptor.decrypt(ciphertext)
    }

    /**
     * SEC-INV-3 防御：对 s_* 列名 / s_* 字段值调用 assert 前缀，防止编码时错传明文列到 UI。
     * 发现错传 → 抛 [IllegalArgumentException]（C.4.8 安全 C 最严格）。
     */
    fun assertSensitiveColumnName(colName: String) {
        if (!colName.startsWith(S_PREFIX)) {
            throw IllegalArgumentException(
                "ZTH SEC-INV-3 违规：列名 $colName 非 s_ 前缀却走了 ZthSensitiveColumnCrypto。" +
                        "禁止把非敏感列当敏感列存（反之亦然）。"
            )
        }
    }
}
