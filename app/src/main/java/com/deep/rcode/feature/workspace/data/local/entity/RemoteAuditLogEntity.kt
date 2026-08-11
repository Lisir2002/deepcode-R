package com.deep.rcode.feature.workspace.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 远程连接审计日志。记录 SSH 连接、凭据操作、备份导入导出、SFTP 大文件传输等事件。
 *
 * 保留上限 10000 条 / 90 天（由 [RemoteAuditLogRepository.enforceRetention] 定期清理）。
 * 连接被删除后日志仍保留，[connectionName]/[remoteHost] 为冗余快照。
 */
@Entity(
    tableName = "remote_audit_logs",
    indices = [Index("createdAt"), Index("category"), Index("connectionId")]
)
data class RemoteAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 审计大类，见 [RemoteAuditCategory]。 */
    val category: String,
    /** 具体动作枚举字符串（CONNECT_OK / AUTH_FAIL 等），见 [RemoteAuditAction]。 */
    val action: String,
    /** 关联的 remote_connections.id；与连接无关的事件为 null。 */
    val connectionId: String? = null,
    /** 连接名快照（冗余），连接删除后日志仍可读。 */
    val connectionName: String? = null,
    /** 主机脱敏快照：只存 "host:port"（不存 username）。 */
    val remoteHost: String? = null,
    /** 事件是否成功。 */
    val success: Boolean,
    /** 错误详情/消息，长度 ≤500（保存时自动截断）。 */
    val message: String? = null,
    /** 对端 IP（可空）；SFTP 事件记录来源 IP 可选。 */
    val sourceIp: String? = null,
    /** 创建时间 epoch ms。 */
    val createdAt: Long
)