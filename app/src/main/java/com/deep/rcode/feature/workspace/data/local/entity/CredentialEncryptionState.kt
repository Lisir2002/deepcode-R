package com.deep.rcode.feature.workspace.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 单行配置表 (id 恒 =1)。所有加密状态（DEK、轮换、生物识别开关）集中存储，避免散落在 Preferences。
 *
 * 由 [CredentialEncryptor.ensureInitialized] 在首次加密/解密前写入第一行。
 * 后续每次轮换或生物识别切换时 upsert 更新。
 */
@Entity(tableName = "credential_encryption_state")
data class CredentialEncryptionStateEntity(
    /** 永远 =1；用 CHECK 限制单行写入由 Migration 保证。 */
    @PrimaryKey val id: Int = 1,

    /** MasterKey 的公参 SHA-256 指纹，用于检测 Keystore 主密钥被重置（≠存值时判异常重置）。 */
    val masterKeyFingerprint: String,

    /** 用 MasterKey AES-256-GCM 加密后的 DEK 原始字节 → B64。 */
    val dekCiphertext: String,

    /** 当前默认写加密版本，固定 "V2"；未来 V3 时这里作为默认写入分支。 */
    val encScheme: String = "V2",

    /** 最后一次轮换 DEK 的 epoch ms；0 = 从未轮换（默认刚初始化）。 */
    val lastRotatedAt: Long = 0L,

    /** 轮换计数，审计用；每次 rotate +1。 */
    val rotationCounter: Int = 0,

    /** 主密钥加密时是否需要生物识别；切换开关时 MasterKey 要 regenerate 并重新 wrap DEK。 */
    val biometricRequired: Boolean = false,

    /** 是否完成 V1（旧裸密文/明文）→ V2 首次迁移；false = 下次启动触发 V1toV2MigrationWorker。 */
    val migratedFromV1: Boolean = false
)