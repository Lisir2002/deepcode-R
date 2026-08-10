package com.deep.rcode.feature.backup.domain

/**
 * 备份加密范围。
 * @param CREDENTIALS_ONLY 仅加密 SSH 密码/API Key/Git Token 等敏感字段
 * @param FULL 全量加密整个 tar.gz 包
 */
enum class BackupEncryptScope {
    CREDENTIALS_ONLY,
    FULL
}