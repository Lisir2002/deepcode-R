package com.R.codecore.core.security

/**
 * 凭据加密 scheme 常量 + 密文格式判定工具函数。
 *
 * 密文格式历史：
 *  - **无前缀**（rc60 及之前）：V1 单层 Keystore AES-256-GCM 密文，或旧明文（兜底）。
 *  - **"V2:" 前缀**（rc61+）：MasterKey-wrapped DEK 双层加密。
 *
 * 判定规则：
 *  - `isV2Ciphertext(s)` → true 时走 DEK 解密。
 *  - `isLikelyV1Ciphertext(s)` → true 时尝试 V1 单密钥解密；失败则返回原文（明文兜底）。
 *  - 两者都不满足 → 视为明文直接返回。
 */
object CredentialEncryptionContract {

    const val SCHEME_V2 = "V2:"
    const val SCHEME_V1 = "" // 无前缀

    /** V2 密文最低长度：至少含 "V2:"(3) + Base64(12IV+1密文+16tag) ≈ 58 字符 */
    const val V2_MIN_LENGTH = 60

    /**
     * 严格判定是否为 V2 格式密文（带 "V2:" 前缀且后续足够长）。
     * 当前写加密固定输出 V2，判定也优先走 V2。
     */
    fun isV2Ciphertext(s: String): Boolean =
        s.startsWith(SCHEME_V2) && s.length >= V2_MIN_LENGTH

    /**
     * 启发式判定是否为 V1 密文或旧明文（无前缀但长得像 Base64 密文）。
     * 用于 V1toV2MigrationWorker 在未知前缀字段中识别哪些是 V1 密文、
     * 哪些是纯明文。
     *
     * 判定规则：无 V2 前缀，且长度 ≥ 58 且全部由 Base64 字符组成（含 = 补位）。
     * 注意：此判定不完美——一段纯英文文本也可能全由 Base64 字符组成，
     * 但长度 ≥ 58 且无 V2 前缀的明文概率极低，V1 密文发生时 decrypt 失败
     * 即回退，对被判断为「V1 密文」但实际是明文的字段，decrypt 成功则正常
     * 迁移，失败则按 unmigrateable 处理（清空字段 + 提示用户重设）。
     */
    fun isLikelyV1Ciphertext(s: String): Boolean =
        !s.startsWith(SCHEME_V2) && s.length >= 58 &&
            s.matches(Regex("^[A-Za-z0-9+/=]+$"))
}