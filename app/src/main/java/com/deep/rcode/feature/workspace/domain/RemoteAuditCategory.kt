package com.deep.rcode.feature.workspace.domain

/**
 * 远程审计日志分类常量。统一埋点字符串，避免硬编码。
 */
object RemoteAuditCategory {
    const val CONNECT = "CONNECT"
    const val CREDENTIAL = "CREDENTIAL"
    const val BACKUP = "BACKUP"
    const val SYNC = "SYNC"
    const val RECONNECT_FAIL = "RECONNECT_FAIL"
    const val SECURITY = "SECURITY"
}

/**
 * 远程审计日志动作常量。每个动作对应一个具体的可读事件。
 */
object RemoteAuditAction {
    // ── CONNECT ──
    const val SSH_CONNECT_OK = "SSH_CONNECT_OK"
    const val SSH_CONNECT_FAIL = "SSH_CONNECT_FAIL"
    const val SSH_DISCONNECT = "SSH_DISCONNECT"
    const val SSH_AUTH_FAIL = "SSH_AUTH_FAIL"
    const val SSH_RECONNECTED = "SSH_RECONNECTED"

    // ── CREDENTIAL ──
    const val CRED_ADD = "CRED_ADD"
    const val CRED_UPDATE = "CRED_UPDATE"
    const val CRED_DELETE = "CRED_DELETE"
    const val CRED_ROTATE_DEK = "CRED_ROTATE_DEK"
    const val CRED_V1_V2_MIGRATE = "CRED_V1_V2_MIGRATE"

    // ── BACKUP ──
    const val BACKUP_EXPORT_OK = "BACKUP_EXPORT_OK"
    const val BACKUP_EXPORT_FAIL = "BACKUP_EXPORT_FAIL"
    const val BACKUP_IMPORT_OK = "BACKUP_IMPORT_OK"
    const val BACKUP_IMPORT_FAIL = "BACKUP_IMPORT_FAIL"

    // ── SYNC ──
    const val SFTP_UPLOAD_BIG = "SFTP_UPLOAD_BIG"
    const val SFTP_DOWNLOAD_BIG = "SFTP_DOWNLOAD_BIG"

    // ── RECONNECT_FAIL ──
    const val TAB_RECONNECT_FAILED_3X = "TAB_RECONNECT_FAILED_3X"

    // ── SECURITY ──
    const val EMERGENCY_RESET_MASTERKEY = "EMERGENCY_RESET_MASTERKEY"
    const val BIOMETRIC_SWITCH = "BIOMETRIC_SWITCH"

    // ── SKILL（RC74：脚本技能沙箱执行审计）──
    const val SKILL_EXEC_OK = "SKILL_EXEC_OK"
    const val SKILL_EXEC_FAIL = "SKILL_EXEC_FAIL"
}