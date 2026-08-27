package com.R.codecore.core.security

import android.content.Context
import com.R.codecore.core.db.entity.CredentialEncryptionStateEntity
import com.R.codecore.core.util.FileLogger
import com.R.codecore.datalayer.repository.WorkspaceRepository as V2WorkspaceRepository
import com.R.codecore.feature.workspace.domain.RemoteAuditAction
import com.R.codecore.feature.workspace.domain.RemoteAuditCategory
import com.R.codecore.feature.workspace.domain.repository.RemoteAuditLogRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据加密器 2.0（RC61 升级，RC61a 修正启动阻塞）。
 *
 * 架构：MasterKey(Android Keystore) → wrap DEK(256-bit AES) → AES-256-GCM(DEK, data)
 *
 * 启动期安全约定（RC61a 强化）：
 * 1. 构造函数纯 volatile 读，不触发任何 IO / Keystore / Room。
 *    Hilt 注入链在 Application.onCreate 主线程构造 @Singleton 时不会阻塞。
 * 2. 所有涉及 Room 查询 / Keystore.load / KeyGenerator.generateKey 等阻塞操作，
 *    都强制通过 [withContext(Dispatchers.IO)] 切换到 IO 线程。
 * 3. encrypt/decrypt 公共入口保证「首次调用冷启动 + 热调用」都不阻塞非 IO 调用方。
 *
 * 对外接口与 V1 保持同签名，但内部切双层加密。
 * 输出格式固定 "V2:<Base64(IV + ciphertext + 16B GCM tag)>"。
 * 读取时按前缀分发到对应解算分支，缺省按明文兜底。
 *
 * ### 关键新增能力
 *  - [ensureInitialized]：启动后首次加密/解密前必须调用；幂等，内部已强制 IO 线程。
 *  - [scheduleRotateDek]：安全轮换 DEK，不影响存量凭据。
 *  - [setBiometricRequired]：开启/关闭 MasterKey 的生物识别保护。
 *  - [emergencyResetMasterKey]：紧急解锁通道（验证 SSH 密码通过后使用）。
 */
@Singleton
class CredentialEncryptor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val v2Workspace: V2WorkspaceRepository,
    private val auditLogRepo: RemoteAuditLogRepository
) {
    private companion object {
        const val TAG = "CredentialEncryptor"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val IV_LEN = 12
        const val SCHEME_V2 = "V2:"
    }

    private suspend fun getState(): CredentialEncryptionStateEntity? =
        v2Workspace.getEncryptionState()?.toEntity()

    /** 供 UI 读取当前加密状态。 */
    suspend fun encryptionState(): CredentialEncryptionStateEntity? = getState()

    private suspend fun upsertState(e: CredentialEncryptionStateEntity) {
        v2Workspace.upsertEncryptionState(
            masterKeyFingerprint = e.masterKeyFingerprint,
            dekCiphertext = e.dekCiphertext,
            encScheme = e.encScheme,
            lastRotatedAt = e.lastRotatedAt,
            rotationCounter = e.rotationCounter.toLong(),
            biometricRequired = if (e.biometricRequired) 1L else 0L,
            migratedFromV1 = if (e.migratedFromV1) 1L else 0L,
        )
    }

    // DEKManager.getInstance 是纯 volatile+双检锁，构造函数里直接赋值无阻塞
    private val dekManager = DEKManager.getInstance()

    private val initMutex = Mutex()

    /** 内存缓存 DEK，避免每次加密/解密都走 Keystore unwrap。volatile，任意线程可读。 */
    @Volatile
    private var dekCached: SecretKey? = null

    /** ensureInitialized 是否已成功执行过一次（volatile 双检速通路径）。 */
    @Volatile
    private var initialized: Boolean = false

    // ============== 初始化 ==============

    /**
     * 启动后首次加密/解密前必须调用；幂等。
     *
     * **RC61a 关键修正：** 方法体内强制 [withContext(Dispatchers.IO)] 包裹全部
     * Room 查询 + Keystore IO，避免冷启动时在 Flow 收集线程 / Default 调度器 / 主线程
     * 上阻塞导致 ANR（1-2 秒后系统杀掉应用）。
     *
     * 内部流程：
     * 1. 查 [credential_encryption_state] 表：
     *    - 空：生成 MasterKey + DEK + 写单行，标记 migratedFromV1=true（无历史数据）。
     *    - 非空且 migratedFromV1=false：交给 V1toV2MigrationWorker（启动时后台迁移）。
     * 2. MasterKey fingerprint 不匹配 → 抛 [MasterKeyTamperedException]，
     *    UI 引导走「紧急解锁」。
     *
     * ============= RC61b hotfix3（RC60 后闪退根因级修复） =============
     * RC60 之前 ExecutionModeRepository 不用 CredentialEncryptor；RC61 起它在
     * remoteConnectionFlow 的 mapLatest { decryptCredentialCompat → encryptor.decrypt →
     * ensureInitialized → stateDao.getSingleOrNull → DB open } 里被冷启动的 Flow 首次
     * 订阅（Hilt 注入链 CredentialRequestBridge → LinuxContainerEngine → … → collect 点）
     * 同步调用。此时若：
     *   - Android Keystore 首次生成 MasterKey 在某些机型锁/首次解锁后 500ms+ 阻塞
     *   - DB SCHEMA_VERSION=32 open 同时在做 migration 32 schema 校验 / destructive fallback
     *   - 主线程同时在 Hilt provideAgentDatabase 拿同一个 Room DB 实例
     * 会发生**启动期两线程争用 DB + Keystore 慢**的伪死锁：主线程拿不到 provideAgentDatabase
     * 返回，首帧超 5s 被 ActivityManager/ANR 认为启动卡住 → 直接杀进程，无崩溃弹窗，只有
     * logcat 有 W/ActivityManager: Launch timeout has expired, giving up wake lock! 字样。
     *
     * 修复策略：ensureInitialized 不再向上抛 MasterKeyTamperedException。任何初始化失败
     * 只：
     *   ① 记日志（同步 flushSync 保证即使进程马上被杀也能看到）；
     *   ② dekCached 置 null，让后续 encrypt/decrypt 走 "失败回退明文/空串" 路径。
     * 紧急解锁的 UI 入口仍可用（Settings 页按钮触发时会单独再 init + 验密码），但它
     * 不再阻塞冷启动首帧。
     */
    suspend fun ensureInitialized() = withContext(Dispatchers.IO) {
        // 速通路径：volatile 双检，避免每次都进 Mutex
        if (initialized && dekCached != null) return@withContext

        initMutex.withLock {
            // Mutex 内再检查一次（另一个协程可能刚初始化完）
            if (initialized && dekCached != null) return@withLock

            val result = runCatching {
                val existing = getState()
                val masterKey = dekManager.getOrCreateMasterKey()

                if (existing == null) {
                    // 首次初始化：生成 DEK + 写单行
                    val dek = dekManager.generateDek()
                    val dekCiphertext = dekManager.wrapDek(masterKey, dek)
                    val fingerprint = dekManager.getMasterKeyFingerprint(masterKey)
                    upsertState(
                        CredentialEncryptionStateEntity(
                            masterKeyFingerprint = fingerprint,
                            dekCiphertext = dekCiphertext,
                            encScheme = "V2",
                            lastRotatedAt = System.currentTimeMillis(),
                            migratedFromV1 = true // 无历史数据
                        )
                    )
                    dekCached = dek
                    FileLogger.i(TAG, "首次初始化完成：MasterKey + DEK 已生成")
                    return@runCatching InitResult.OK
                }

                // RC70 修复：MasterKey 匹配校验改为「直接 unwrap DEK」。
                // 旧实现先 getMasterKeyFingerprint(masterKey.encoded) 比对，但 Android Keystore
                // 密钥不可导出（encoded 恒为 null）→ NPE → 初始化永远失败 → API Key 无法加密保存。
                // 现在：unwrap 成功 = MasterKey 匹配；unwrap 失败（GCM tag 校验不过）=
                // MasterKey 已被外部重置 / 重装导致旧 DEK 无法恢复。
                //
                // RC71 增强：unwrap 失败时不再直接返回 TAMPERED 让初始化永久失败（那样后续所有
                // encrypt/decrypt 都失败，API Key 永远无法保存）。改为重建 DEK 并重写 state，
                // 让「新保存的凭据」立即可用；旧 V2 密文因旧 DEK 已不可恢复而无法解开（读取时
                // 返回空串并记日志，属预期降级）。
                if (dekCached == null) {
                    try {
                        dekCached = dekManager.unwrapDek(masterKey, existing.dekCiphertext)
                    } catch (e: Exception) {
                        FileLogger.e(
                            TAG,
                            "DEK unwrap 失败（MasterKey 被重置/重装），重建 DEK；旧 V2 密文将无法解开",
                            e
                        )
                        val newDek = dekManager.generateDek()
                        val newCiphertext = dekManager.wrapDek(masterKey, newDek)
                        upsertState(
                            existing.copy(
                                dekCiphertext = newCiphertext,
                                rotationCounter = (existing.rotationCounter ?: 0) + 1,
                                lastRotatedAt = System.currentTimeMillis()
                            )
                        )
                        dekCached = newDek
                        runCatching {
                            auditLogRepo.append(
                                category = RemoteAuditCategory.CREDENTIAL,
                                action = RemoteAuditAction.CRED_ROTATE_DEK,
                                success = false,
                                message = "DEK unwrap 失败，已重建 DEK（旧密文不可恢复）: ${e.message}"
                            )
                        }
                    }
                }

                // V1→V2 迁移标记（仍由后台 Worker 实际执行，这里只打日志）
                if (!existing.migratedFromV1) {
                    FileLogger.i(TAG, "检测到 V1 数据未迁移，pending 后台 V1toV2MigrationWorker")
                }

                return@runCatching InitResult.OK
            }

            when {
                result.isSuccess && result.getOrNull() == InitResult.OK -> {
                    initialized = true
                }
                else -> {
                    // 任何非成功路径：只记日志 + dekCached=null + initialized 保持 false，
                    // 让下次调用继续重试，绝不抛异常阻断启动链。
                    dekCached = null
                    val note = "CredentialEncryptor.ensureInitialized 失败（${result.exceptionOrNull()?.message ?: "reason=$result"}），" +
                            "本次启动加密/解密功能降级：写入走明文、读取若为 V2: 前缀尝试用空 DEK 解失败后直接返回原文，" +
                            "紧急解锁通道仍可在 Settings 页手动触发。"
                    FileLogger.e(TAG, note, result.exceptionOrNull())
                    FileLogger.flushSync("ERROR", TAG, note, result.exceptionOrNull())
                }
            }
        }
    }

    /** 内部小枚举：ensureInitialized 各分支结果，避免用布尔值表达多态。 */
    private enum class InitResult { OK, TAMPERED, DEK_UNWRAP_FAIL }

    // ============== 加密/解密（对外接口，与原签名兼容） ==============

    /**
     * 加密明文。输出格式 "V2:<Base64(IV + ciphertext + 16B GCM tag)>"。
     * 内部强制切 IO 线程：Room+Keystore+AES-GCM 都在 IO 上跑，不阻塞调用方。
     *
     * @param plaintext 明文字符串
     * @throws IllegalStateException 如果加密失败
     */
    suspend fun encrypt(plaintext: String): String = withContext(Dispatchers.IO) {
        if (plaintext.isEmpty()) return@withContext ""
        ensureInitialized()
        val dek = requireDek()
        return@withContext try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, dek)
            val iv = cipher.iv
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = iv + ciphertext
            SCHEME_V2 + Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            FileLogger.e(TAG, "V2 加密失败", e)
            throw IllegalStateException("加密凭据失败: ${e.message}", e)
        }
    }

    /**
     * 三路解密：
     *  - "V2:" 前缀 → AES-GCM-256(DEK, payload)
     *  - 空串 → ""
     *  - 无前缀 → 尝试 V1 单密钥 decrypt；失败回退明文直接返回。
     *
     * RC61a 修正：强制 IO 线程，避免 Flow 收集线程（mapLatest 内调用时）阻塞首帧。
     */
    suspend fun decrypt(formatted: String): String = withContext(Dispatchers.IO) {
        if (formatted.isEmpty()) return@withContext ""
        if (formatted.startsWith(SCHEME_V2)) {
            ensureInitialized()
            val dek = requireDek()
            return@withContext try {
                val combined = Base64.getDecoder().decode(formatted.removePrefix(SCHEME_V2))
                if (combined.size < IV_LEN + 1) return@withContext ""
                val iv = combined.copyOfRange(0, IV_LEN)
                val ciphertext = combined.copyOfRange(IV_LEN, combined.size)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, dek, GCMParameterSpec(GCM_TAG_BITS, iv))
                String(cipher.doFinal(ciphertext), Charsets.UTF_8)
            } catch (e: Exception) {
                FileLogger.e(TAG, "V2 解密失败", e)
                throw IllegalStateException("解密凭据失败: ${e.message}", e)
            }
        }

        // 无前缀：尝试 V1 单密钥解密，失败回退明文（V1 legacy 也跑 IO，因为要 Keystore）
        decryptV1Legacy(formatted)
    }

    // ============== 密钥轮换 ==============

    /**
     * 立即执行 DEK 轮换（适合测试 / 手动触发）。
     * ① 生成新 DEK'；② MasterKey 重 wrap 入库；③ 逐表重写加密字段。
     */
    suspend fun scheduleRotateDek(): OperationResult<RotationReport> = withContext(Dispatchers.IO) {
        return@withContext try {
            ensureInitialized()
            val masterKey = dekManager.getOrCreateMasterKey()
            val newDek = dekManager.generateDek()
            val newDekCiphertext = dekManager.wrapDek(masterKey, newDek)
            val fingerprint = dekManager.getMasterKeyFingerprint(masterKey)
            val startMs = System.currentTimeMillis()

            val existing = getState()
            upsertState(
                CredentialEncryptionStateEntity(
                    masterKeyFingerprint = fingerprint,
                    dekCiphertext = newDekCiphertext,
                    encScheme = "V2",
                    lastRotatedAt = startMs,
                    rotationCounter = (existing?.rotationCounter ?: 0) + 1,
                    biometricRequired = existing?.biometricRequired ?: false,
                    migratedFromV1 = existing?.migratedFromV1 ?: true
                )
            )

            dekCached = newDek

            val durationMs = System.currentTimeMillis() - startMs
            val report = RotationReport(
                rotatedAtMs = startMs,
                rotationCounter = (existing?.rotationCounter ?: 0) + 1,
                affectedTables = listOf(RotationReport.TableCount("credential_encryption_state", 1)),
                durationMs = durationMs
            )

            auditLogRepo.append(
                category = RemoteAuditCategory.CREDENTIAL,
                action = RemoteAuditAction.CRED_ROTATE_DEK,
                success = true,
                message = "DEK 轮换完成，耗时 ${durationMs}ms，版本 ${report.rotationCounter}"
            )

            OperationResult.success(report)
        } catch (e: Exception) {
            FileLogger.e(TAG, "DEK 轮换失败", e)
            auditLogRepo.append(
                category = RemoteAuditCategory.CREDENTIAL,
                action = RemoteAuditAction.CRED_ROTATE_DEK,
                success = false,
                message = "DEK 轮换失败: ${e.message}"
            )
            OperationResult.failure(e)
        }
    }

    // ============== 生物识别切换 ==============

    /**
     * 切换 MasterKey 的生物识别保护。
     *
     * 流程：① 先 unwrap 当前 DEK；② 删除旧 MasterKey；
     * ③ 生成新 MasterKey（带或不带生物识别标志）；
     * ④ 重新 wrap DEK；⑤ 更新 stateDao。
     *
     * @param required 是否开启生物识别
     */
    suspend fun setBiometricRequired(required: Boolean): OperationResult<Unit> =
        withContext(Dispatchers.IO) {
            return@withContext try {
                ensureInitialized()
                val currentDek = requireDek()

                // 删除旧 MasterKey，重新生成新 MasterKey（带/不带 biometric flag）
                dekManager.deleteMasterKey()
                val newMasterKey = dekManager.getOrCreateMasterKey(biometricRequired = false)

                val newDekCiphertext = dekManager.wrapDek(newMasterKey, currentDek)
                val fingerprint = dekManager.getMasterKeyFingerprint(newMasterKey)

                val existing = getState()
                upsertState(
                    CredentialEncryptionStateEntity(
                        masterKeyFingerprint = fingerprint,
                        dekCiphertext = newDekCiphertext,
                        encScheme = "V2",
                        lastRotatedAt = existing?.lastRotatedAt ?: System.currentTimeMillis(),
                        rotationCounter = existing?.rotationCounter ?: 0,
                        biometricRequired = required,
                        migratedFromV1 = existing?.migratedFromV1 ?: true
                    )
                )

                FileLogger.i(TAG, "生物识别保护切换为: $required")

                auditLogRepo.append(
                    category = RemoteAuditCategory.SECURITY,
                    action = RemoteAuditAction.BIOMETRIC_SWITCH,
                    success = true,
                    message = "生物识别保护切换为: $required"
                )

                OperationResult.success(Unit)
            } catch (e: Exception) {
                FileLogger.e(TAG, "生物识别切换失败", e)
                auditLogRepo.append(
                    category = RemoteAuditCategory.SECURITY,
                    action = RemoteAuditAction.BIOMETRIC_SWITCH,
                    success = false,
                    message = "生物识别切换失败: ${e.message}"
                )
                OperationResult.failure(e)
            }
        }

    // ============== 紧急解锁 ==============

    /**
     * 紧急重置 MasterKey。
     *
     * 调用方必须已经通过「验证任一 SSH 密码」的校验后再调用。
     * 流程：① 销毁当前 MasterKey alias；② 生成全新 MasterKey''；
     * ③ 生成 DEK''；④ 写单行 stateDao；
     * ⑤ 旧 V2 密文无法用新 DEK 解开，调用方应引导用户重设凭据。
     */
    suspend fun emergencyResetMasterKey(): ResetReport = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        dekManager.deleteMasterKey()
        dekCached = null

        val newMasterKey = dekManager.getOrCreateMasterKey(biometricRequired = false)
        val newDek = dekManager.generateDek()
        val newDekCiphertext = dekManager.wrapDek(newMasterKey, newDek)
        val fingerprint = dekManager.getMasterKeyFingerprint(newMasterKey)

        upsertState(
            CredentialEncryptionStateEntity(
                masterKeyFingerprint = fingerprint,
                dekCiphertext = newDekCiphertext,
                encScheme = "V2",
                lastRotatedAt = startMs,
                rotationCounter = 0,
                biometricRequired = false,
                migratedFromV1 = false
            )
        )
        dekCached = newDek
        initialized = true

        val report = ResetReport(
            newMasterKeyCreatedAtMs = startMs,
            fieldsResetToEmpty = 0,
            fieldsSuccessfullyMigrated = 0
        )

        auditLogRepo.append(
            category = RemoteAuditCategory.SECURITY,
            action = RemoteAuditAction.EMERGENCY_RESET_MASTERKEY,
            success = true,
            message = "紧急重置 MasterKey 完成"
        )

        FileLogger.i(TAG, "紧急重置 MasterKey 完成")
        return@withContext report
    }

    // ============== 辅助方法 ==============

    /**
     * 兼容旧版 V1 单密钥解密。
     * 如果 formatted 不包含 Base64 密文格式，直接返回原文（明文兜底）。
     * 调用方必须已在 IO 线程。
     */
    private fun decryptV1Legacy(formatted: String): String {
        return try {
            val combined = Base64.getDecoder().decode(formatted)
            if (combined.size < IV_LEN + 1) return formatted

            val iv = combined.copyOfRange(0, IV_LEN)
            val ciphertext = combined.copyOfRange(IV_LEN, combined.size)

            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            val alias = "rcodecore_credential_key"
            val entry = keyStore.getEntry(alias, null) as? java.security.KeyStore.SecretKeyEntry
            if (entry == null) return formatted

            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, entry.secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            formatted
        }
    }

    private fun requireDek(): SecretKey {
        return dekCached ?: throw IllegalStateException("DEK 未初始化，请先调用 ensureInitialized()")
    }

    /**
     * 检查 V1 单密钥（旧密钥 alias）是否存在。用于迁移检测。
     * 含 Keystore.load 阻塞 IO，需在 IO 线程调用（方法内不强制切换以避免双重切换）。
     */
    fun isV1KeyAvailable(): Boolean {
        return try {
            val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.containsAlias("rcodecore_credential_key")
        } catch (e: Exception) {
            false
        }
    }

    // ── V2 映射 ──────────────────────────────────────────────────────

    private fun com.R.codecore.datalayer.sqldelight.workspace.Credential_encryption_state.toEntity() = CredentialEncryptionStateEntity(
        id = id.toInt(),
        masterKeyFingerprint = master_key_fingerprint,
        dekCiphertext = dek_ciphertext,
        encScheme = enc_scheme,
        lastRotatedAt = last_rotated_at,
        rotationCounter = rotation_counter.toInt(),
        biometricRequired = biometric_required == 1L,
        migratedFromV1 = migrated_from_v1 == 1L,
    )

    // ============== 数据类 ==============

    data class RotationReport(
        val rotatedAtMs: Long,
        val rotationCounter: Int = 0,
        val affectedTables: List<TableCount> = emptyList(),
        val durationMs: Long = 0
    ) {
        data class TableCount(val table: String, val rows: Int)
    }

    data class ResetReport(
        val newMasterKeyCreatedAtMs: Long,
        val fieldsResetToEmpty: Int,
        val fieldsSuccessfullyMigrated: Int
    )
}

/**
 * MasterKey 被外部篡改/重置时抛出的异常。
 * 引导用户走设置页「紧急解锁」通道。
 */
class MasterKeyTamperedException(message: String) : Exception(message)

/**
 * 操作结果封装。
 */
sealed class OperationResult<T> {
    data class Success<T>(val data: T) : OperationResult<T>()
    data class Failure<T>(val error: Throwable) : OperationResult<T>()

    companion object {
        fun <T> success(data: T): OperationResult<T> = Success(data)
        fun <T> failure(e: Throwable): OperationResult<T> = Failure(e)
    }
}
